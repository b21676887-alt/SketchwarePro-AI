package dev.aldi.sayuti.editor.manage;

import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.createLibraryMap;
import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.deleteSelectedLocalLibraries;
import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.getAllLocalLibraries;
import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.getLocalLibFile;
import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.getLocalLibraries;
import static dev.aldi.sayuti.editor.manage.LocalLibrariesUtil.rewriteLocalLibFile;

import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.Gson;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

import a.a.a.MA;
import a.a.a.mB;
import mod.hey.studios.build.BuildSettings;
import mod.hey.studios.util.Helper;
import pro.sketchware.R;
import pro.sketchware.databinding.ManageLocallibrariesBinding;
import pro.sketchware.databinding.ViewItemLocalLibBinding;
import pro.sketchware.databinding.ViewItemLocalLibSearchBinding;
import pro.sketchware.utility.SketchwareUtil;

public class ManageLocalLibraryActivity extends BaseAppCompatActivity {
    private final LibraryAdapter adapter = new LibraryAdapter();
    private final SearchAdapter searchAdapter = new SearchAdapter();
    private ArrayList<HashMap<String, Object>> projectUsedLibs = new ArrayList<>();
    private boolean notAssociatedWithProject;
    private boolean searchBarExpanded;
    private BuildSettings buildSettings;
    private ManageLocallibrariesBinding binding;
    private String scId;

    // ==================== Repository Management ====================

    private Runnable repoDialogRefresh;
    
    private static final String REPOSITORIES_JSON_PATH = getExternalStorageDir().concat("/.sketchware/libs/repositories.json");

    private static final String[] BUILTIN_REPOS = {
            "Maven Central", "Google Maven Official", "JitPack", "Sonatype", "Google R8 Direct Releases"
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        binding = ManageLocallibrariesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        {
            View view1 = binding.searchBar;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view1.getLayoutParams();

            int end = lp.getMarginEnd();
            int start = lp.getMarginStart();

            ViewCompat.setOnApplyWindowInsetsListener(view1, (v, i) -> {
                Insets insets = i.getInsets(WindowInsetsCompat.Type.displayCutout());
                lp.setMarginEnd(end + insets.right);
                lp.setMarginStart(start + insets.left);
                v.setLayoutParams(lp);
                return i;
            });
        }

        {
            View view1 = binding.contextualToolbarContainer;
            int left = view1.getPaddingLeft();
            int top = view1.getPaddingTop();
            int right = view1.getPaddingRight();
            int bottom = view1.getPaddingBottom();

            ViewCompat.setOnApplyWindowInsetsListener(view1, (v, i) -> {
                Insets insets = i.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(left + insets.left, top + insets.top, right + insets.right, bottom);
                return i;
            });
        }

        {
            View view1 = binding.librariesList;
            int left = view1.getPaddingLeft();
            int top = view1.getPaddingTop();
            int right = view1.getPaddingRight();
            int bottom = view1.getPaddingBottom();

            ViewCompat.setOnApplyWindowInsetsListener(view1, (v, i) -> {
                Insets insets = i.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(left + insets.left, top, right + insets.right, bottom + insets.bottom);
                return i;
            });
        }

        {
            View view1 = binding.searchList;
            int left = view1.getPaddingLeft();
            int top = view1.getPaddingTop();
            int right = view1.getPaddingRight();
            int bottom = view1.getPaddingBottom();

            ViewCompat.setOnApplyWindowInsetsListener(view1, (v, i) -> {
                Insets insets = i.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(left + insets.left, top, right + insets.right, bottom + insets.bottom);
                return i;
            });
        }

        {
            View view1 = binding.downloadLibraryButton;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view1.getLayoutParams();
            int bottom = lp.bottomMargin;

            ViewCompat.setOnApplyWindowInsetsListener(view1, (v, i) -> {
                Insets insets = i.getInsets(WindowInsetsCompat.Type.systemBars());
                lp.bottomMargin = bottom + insets.bottom;
                v.setLayoutParams(lp);
                return i;
            });
        }

        if (getIntent().hasExtra("sc_id")) {
            scId = Objects.requireNonNull(getIntent().getStringExtra("sc_id"));
            buildSettings = new BuildSettings(scId);
            notAssociatedWithProject = scId.equals("system");
        }

        adapter.setOnLocalLibrarySelectedStateChangedListener(item -> {
            long selectedItemCount = getSelectedLocalLibrariesCount();
            if (selectedItemCount > 0 && adapter.isSelectionModeEnabled) {
                binding.contextualToolbar.setTitle(String.valueOf(selectedItemCount));
                expandContextualToolbar();
            } else {
                adapter.isSelectionModeEnabled = false;
                collapseContextualToolbar();
            }
        });

        binding.librariesList.setAdapter(adapter);
        binding.searchList.setAdapter(searchAdapter);

        binding.searchBar.setNavigationOnClickListener(v -> {
            if (!mB.a()) {
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        binding.btnMoreOptions.setOnClickListener(this::showOptionsMenu);

        binding.contextualToolbar.setNavigationOnClickListener(v -> hideContextualToolbarAndClearSelection());
        binding.contextualToolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_select_all) {
                setLocalLibrariesSelected(true);
                binding.contextualToolbar.setTitle(String.valueOf(getSelectedLocalLibrariesCount()));
                return true;
            } else if (id == R.id.action_delete_selected_local_libraries) {
                k();
                Executors.newSingleThreadExecutor().execute(() -> {
                    deleteSelectedLocalLibraries(scId, adapter.getLocalLibraries(), projectUsedLibs);
                    runOnUiThread(() -> {
                        h();
                        SketchwareUtil.toast("Deleted successfully");
                        adapter.isSelectionModeEnabled = false;
                        collapseContextualToolbar();
                        runLoadLocalLibrariesTask();
                    });
                });

                return true;
            }
            return false;
        });

        binding.downloadLibraryButton.setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentByTag("library_downloader_dialog") != null) {
                return;
            }

            Bundle bundle = new Bundle();
            bundle.putBoolean("notAssociatedWithProject", notAssociatedWithProject);
            bundle.putSerializable("buildSettings", buildSettings);
            bundle.putString("localLibFile", getLocalLibFile(scId).getAbsolutePath());

            LibraryDownloaderDialogFragment fragment = new LibraryDownloaderDialogFragment();
            fragment.setArguments(bundle);
            fragment.setOnLibraryDownloadedTask(this::runLoadLocalLibrariesTask);
            fragment.show(getSupportFragmentManager(), "library_downloader_dialog");
        });

        binding.searchView.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String value = s.toString().trim();
                searchAdapter.filter(getAdapterLocalLibraries(), value);
            }

            @Override
            public void onTextChanged(CharSequence newText, int start, int before, int count) {
            }
        });

        runLoadLocalLibrariesTask();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (searchBarExpanded) {
                    hideContextualToolbarAndClearSelection();
                } else if (binding.searchView.isShowing()) {
                    binding.searchView.hide();
                } else {
                    finish();
                }
            }
        });
    }

    private void showOptionsMenu(View anchorView) {
        androidx.appcompat.widget.PopupMenu popupMenu = new androidx.appcompat.widget.PopupMenu(this, anchorView);

        popupMenu.getMenu().add(0, 1, 0, "إعادة تحميل المكتبات");
        popupMenu.getMenu().add(0, 2, 1, "تحديد الكل");
        popupMenu.getMenu().add(0, 3, 2, "إدارة المستودعات");

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == 1) {
                runLoadLocalLibrariesTask();
                return true;
            } else if (itemId == 2) {
                adapter.isSelectionModeEnabled = true;
                setLocalLibrariesSelected(true);
                expandContextualToolbar();
                binding.contextualToolbar.setTitle(String.valueOf(getSelectedLocalLibrariesCount()));
                return true;
            } else if (itemId == 3) {
                showManageRepositoriesDialog();
                return true;
            }
            return false;
        });

        popupMenu.show();
    }

    private ArrayList<HashMap<String, Object>> loadCustomRepos() {
        File file = new File(REPOSITORIES_JSON_PATH);
        if (!file.exists()) {
            seedDefaultRepos(file);
        }
        if (file.exists()) {
            try {
                ArrayList<HashMap<String, Object>> list = new Gson().fromJson(
                        pro.sketchware.utility.FileUtil.readFile(file.getAbsolutePath()),
                        Helper.TYPE_MAP_LIST);
                if (list != null) return list;
            } catch (Exception e) {
                Log.e("ManageLocalLibrary", "Failed to parse repositories.json", e);
            }
        }
        return new ArrayList<>();
    }

    private void seedDefaultRepos(File file) {
        ArrayList<HashMap<String, Object>> defaults = new ArrayList<>();
        String[][] defaultEntries = {
                {"HortanWorks", "https://repo.hortonworks.com/content/repositories/releases"},
                {"Atlassian", "https://maven.atlassian.com/content/repositories/atlassian-public"},
                {"JCenter", "https://jcenter.bintray.com"},
                {"Sonatype", "https://oss.sonatype.org/content/repositories/releases"},
                {"Spring Plugins", "https://repo.spring.io/plugins-release"},
                {"Spring Milestone", "https://repo.spring.io/libs-milestone"},
                {"Apache Maven", "https://repo.maven.apache.org/maven2"},
                {"JitPack", "https://jitpack.io"},
                {"Maven Central", "https://repo1.maven.org/maven2"},
                {"Google Maven Official", "https://dl.google.com/dl/android/maven2"},
                {"Google R8 Direct Releases", "https://storage.googleapis.com/r8-releases/raw"}
        };
        for (String[] entry : defaultEntries) {
            HashMap<String, Object> repo = new HashMap<>();
            repo.put("name", entry[0]);
            repo.put("url", entry[1]);
            defaults.add(repo);
        }
        file.getParentFile().mkdirs();
        pro.sketchware.utility.FileUtil.writeFile(file.getAbsolutePath(), new Gson().toJson(defaults));
    }

    private void saveCustomRepos(ArrayList<HashMap<String, Object>> repos) {
        File file = new File(REPOSITORIES_JSON_PATH);
        file.getParentFile().mkdirs();
        pro.sketchware.utility.FileUtil.writeFile(file.getAbsolutePath(), new Gson().toJson(repos));
    }

    private void showManageRepositoriesDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_manage_repositories, null);
        TextView builtinRepos = dialogView.findViewById(R.id.builtin_repos);
        LinearLayout customReposContainer = dialogView.findViewById(R.id.custom_repos_container);
        TextView emptyMessage = dialogView.findViewById(R.id.empty_message);
        ImageButton btnAdd = dialogView.findViewById(R.id.btn_add_repo);

        StringBuilder builtinText = new StringBuilder();
        for (String name : BUILTIN_REPOS) {
            if (builtinText.length() > 0) builtinText.append('\n');
            builtinText.append("• ").append(name);
        }
        builtinRepos.setText(builtinText);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(dialogView);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_repo_title)
                .setView(scrollView)
                .setPositiveButton(R.string.common_word_ok, null)
                .create();

        Runnable refreshCustomRepos = () -> {
            customReposContainer.removeAllViews();
            ArrayList<HashMap<String, Object>> repos = loadCustomRepos();
            emptyMessage.setVisibility(repos.isEmpty() ? View.VISIBLE : View.GONE);
            for (int i = 0; i < repos.size(); i++) {
                final int index = i;
                HashMap<String, Object> repo = repos.get(i);
                String name = repo.get("name") instanceof String ? (String) repo.get("name") : "";
                String url = repo.get("url") instanceof String ? (String) repo.get("url") : "";

                View itemView = LayoutInflater.from(this).inflate(R.layout.item_repository, customReposContainer, false);
                ((TextView) itemView.findViewById(R.id.repo_name)).setText(name);
                ((TextView) itemView.findViewById(R.id.repo_url)).setText(url);

                itemView.findViewById(R.id.btn_edit).setOnClickListener(v ->
                        showAddEditRepoDialog(name, url, index));
                itemView.findViewById(R.id.btn_delete).setOnClickListener(v ->
                        showDeleteRepoDialog(name, index));

                customReposContainer.addView(itemView);
            }
        };
        refreshCustomRepos.run();
        repoDialogRefresh = refreshCustomRepos;

        btnAdd.setOnClickListener(v -> showAddEditRepoDialog("", "", -1));

        dialog.setOnDismissListener(d -> repoDialogRefresh = null);
        dialog.show();
    }

    private void refreshRepoDialog() {
        if (repoDialogRefresh != null) {
            repoDialogRefresh.run();
        }
    }

    private void showAddEditRepoDialog(String currentName, String currentUrl, int editIndex) {
        boolean isEdit = editIndex >= 0;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, (int) (16 * getResources().getDisplayMetrics().density), padding, 0);

        TextInputLayout nameLayout = new TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        nameLayout.setHint(getString(R.string.dialog_repo_name_hint));
        nameLayout.setPlaceholderText(getString(R.string.dialog_repo_name_placeholder));
        TextInputEditText nameInput = new TextInputEditText(nameLayout.getContext());
        nameInput.setText(currentName);
        nameInput.setSingleLine(true);
        nameLayout.addView(nameInput);

        TextInputLayout urlLayout = new TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        urlLayout.setHint(getString(R.string.dialog_repo_url_hint));
        urlLayout.setPlaceholderText(getString(R.string.dialog_repo_url_placeholder));
        TextInputEditText urlInput = new TextInputEditText(urlLayout.getContext());
        urlInput.setText(currentUrl);
        urlInput.setSingleLine(true);
        urlLayout.addView(urlInput);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
        layout.addView(nameLayout, params);
        layout.addView(urlLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        androidx.appcompat.app.AlertDialog addEditDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(isEdit ? R.string.dialog_repo_edit_title : R.string.dialog_repo_add_title)
                .setView(layout)
                .setPositiveButton(R.string.common_word_save, null)
                .setNegativeButton(R.string.common_word_cancel, null)
                .create();

        addEditDialog.show();

        addEditDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            nameLayout.setError(null);
            urlLayout.setError(null);

            String name = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
            String url = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";

            if (name.isEmpty()) {
                nameLayout.setError(getString(R.string.dialog_repo_error_name_required));
                return;
            }
            if (url.isEmpty()) {
                urlLayout.setError(getString(R.string.dialog_repo_error_url_required));
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                urlLayout.setError(getString(R.string.dialog_repo_error_url_invalid));
                return;
            }
            if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

            ArrayList<HashMap<String, Object>> repos = loadCustomRepos();
            HashMap<String, Object> entry = new HashMap<>();
            entry.put("name", name);
            entry.put("url", url);

            if (isEdit && editIndex < repos.size()) {
                repos.set(editIndex, entry);
            } else {
                repos.add(entry);
            }
            saveCustomRepos(repos);
            refreshRepoDialog();
            addEditDialog.dismiss();
        });
    }

    private void showDeleteRepoDialog(String repoName, int index) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(String.format(getString(R.string.dialog_repo_delete_confirm), repoName))
                .setPositiveButton(R.string.common_word_delete, (d, w) -> {
                    ArrayList<HashMap<String, Object>> repos = loadCustomRepos();
                    if (index < repos.size()) {
                        repos.remove(index);
                        saveCustomRepos(repos);
                        refreshRepoDialog();
                    }
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private void runLoadLocalLibrariesTask() {
        k();
        new Handler().postDelayed(() -> new LoadLocalLibrariesTask(this).execute(), 500L);
    }

    private List<LocalLibrary> getAdapterLocalLibraries() {
        return adapter.getLocalLibraries();
    }

    private void hideContextualToolbarAndClearSelection() {
        adapter.isSelectionModeEnabled = false;
        if (collapseContextualToolbar()) {
            setLocalLibrariesSelected(false);
        }
    }

    public void setLocalLibrariesSelected(boolean selected) {
        for (LocalLibrary library : getAdapterLocalLibraries()) {
            library.setSelected(selected);
        }
        adapter.notifyDataSetChanged();
    }

    private void expandContextualToolbar() {
        searchBarExpanded = true;
        binding.searchBar.expand(binding.contextualToolbarContainer, binding.appBarLayout);
    }

    private boolean collapseContextualToolbar() {
        searchBarExpanded = false;
        return binding.searchBar.collapse(binding.contextualToolbarContainer, binding.appBarLayout);
    }

    private long getSelectedLocalLibrariesCount() {
        long count = 0;
        for (LocalLibrary library : getAdapterLocalLibraries()) {
            if (library.isSelected()) {
                count++;
            }
        }
        return count;
    }

    private void loadLibraries() {
        var localLibraries = getAllLocalLibraries();
        if (!notAssociatedWithProject) {
            projectUsedLibs = getLocalLibraries(scId);
        }

        localLibraries.sort((lib1, lib2) -> {
            boolean isEnabled1 = isUsedLibrary(lib1.getName());
            boolean isEnabled2 = isUsedLibrary(lib2.getName());
            return Boolean.compare(isEnabled2, isEnabled1);
        });

        runOnUiThread(() -> {
            adapter.setLocalLibraries(localLibraries);
            binding.noContentLayout.setVisibility(localLibraries.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private boolean isUsedLibrary(String libraryName) {
        if (!notAssociatedWithProject && projectUsedLibs != null) {
            for (Map<String, Object> libraryMap : projectUsedLibs) {
                if (libraryName.equals(libraryMap.get("name").toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateLibraryUsage(LocalLibrary library, boolean isChecked) {
        if (notAssociatedWithProject || projectUsedLibs == null) return;

        String name = library.getName();
        if (!isChecked) {
            int indexToRemove = -1;
            for (int i = 0; i < projectUsedLibs.size(); i++) {
                Map<String, Object> libraryMap = projectUsedLibs.get(i);
                if (name.equals(libraryMap.get("name").toString())) {
                    indexToRemove = i;
                    break;
                }
            }
            if (indexToRemove != -1) {
                projectUsedLibs.remove(indexToRemove);
            }
        } else {
            boolean alreadyExists = false;
            String dependency = null;
            for (Map<String, Object> libraryMap : projectUsedLibs) {
                if (name.equals(libraryMap.get("name").toString())) {
                    alreadyExists = true;
                    break;
                }
            }
            if (!alreadyExists) {
                HashMap<String, Object> localLibrary = createLibraryMap(name, dependency);
                projectUsedLibs.add(localLibrary);
            }
        }
        rewriteLocalLibFile(scId, new Gson().toJson(projectUsedLibs));
    }

    public interface OnLocalLibrarySelectedStateChangedListener {
        void invoke(LocalLibrary library);
    }

    private static class LoadLocalLibrariesTask extends MA {
        private final WeakReference<ManageLocalLibraryActivity> activity;

        public LoadLocalLibrariesTask(ManageLocalLibraryActivity activity) {
            super(activity);
            this.activity = new WeakReference<>(activity);
            activity.addTask(this);
        }

        @Override
        public void a() {
            activity.get().h();
        }

        @Override
        public void a(String idk) {
            activity.get().h();
        }

        @Override
        public void b() {
            try {
                activity.get().loadLibraries();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.ViewHolder> {
        private final List<LocalLibrary> localLibraries = new ArrayList<>();
        public boolean isSelectionModeEnabled;
        private @Nullable OnLocalLibrarySelectedStateChangedListener onLocalLibrarySelectedStateChangedListener;

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(ViewItemLocalLibBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            LocalLibrary library = localLibraries.get(position);
            var binding = holder.binding;

            binding.libraryName.setText(library.getName());
            binding.librarySize.setText(library.getSize());
            binding.libraryName.setSelected(true);
            bindSelectedState(binding.card, library);

            binding.card.setOnClickListener(v -> {
                if (isSelectionModeEnabled) {
                    toggleLocalLibrary(binding.card, library, onLocalLibrarySelectedStateChangedListener);
                } else if (!notAssociatedWithProject) {
                    binding.materialSwitch.performClick();
                }
            });

            binding.card.setOnLongClickListener(v -> {
                if (isSelectionModeEnabled) {
                    return false;
                }

                isSelectionModeEnabled = true;
                toggleLocalLibrary(binding.card, library, onLocalLibrarySelectedStateChangedListener);
                return true;
            });

            binding.materialSwitch.setChecked(isUsedLibrary(library.getName()));
            if (!notAssociatedWithProject) {
                binding.materialSwitch.setEnabled(true);
                binding.materialSwitch.setOnClickListener(v -> updateLibraryUsage(library, binding.materialSwitch.isChecked()));
            } else {
                binding.materialSwitch.setEnabled(false);
            }
        }

        @Override
        public int getItemCount() {
            return localLibraries.size();
        }

        public void setOnLocalLibrarySelectedStateChangedListener(
                @Nullable OnLocalLibrarySelectedStateChangedListener onLocalLibrarySelectedStateChangedListener) {
            this.onLocalLibrarySelectedStateChangedListener = onLocalLibrarySelectedStateChangedListener;
        }

        private void toggleLocalLibrary(MaterialCardView card, LocalLibrary library,
                                        @Nullable OnLocalLibrarySelectedStateChangedListener onLocalLibrarySelectedStateChangedListener) {
            library.setSelected(!library.isSelected());
            bindSelectedState(card, library);
            if (onLocalLibrarySelectedStateChangedListener != null) {
                onLocalLibrarySelectedStateChangedListener.invoke(library);
            }
            if (library.isSelected() && isUsedLibrary(library.getName())) {
                new MaterialAlertDialogBuilder(ManageLocalLibraryActivity.this)
                        .setTitle("Warning")
                        .setMessage("This library \"" + library.getName() + "\" already used in your project, removing it may break your project\rDo you want to continue removing it?")
                        .setPositiveButton(Helper.getResString(R.string.common_word_yes), (dialog, which) -> dialog.dismiss())
                        .setNegativeButton(Helper.getResString(R.string.common_word_cancel), (dialog, which) -> {
                            toggleLocalLibrary(card, library, onLocalLibrarySelectedStateChangedListener);
                            dialog.dismiss();
                        })
                        .show();
            }
        }

        private void bindSelectedState(MaterialCardView card, LocalLibrary library) {
            card.setChecked(library.isSelected());
        }

        public List<LocalLibrary> getLocalLibraries() {
            return localLibraries;
        }

        public void setLocalLibraries(List<LocalLibrary> localLibraries) {
            this.localLibraries.clear();
            this.localLibraries.addAll(localLibraries);
            notifyDataSetChanged();
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            private final ViewItemLocalLibBinding binding;

            public ViewHolder(@NonNull ViewItemLocalLibBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    public class SearchAdapter extends RecyclerView.Adapter<SearchAdapter.ViewHolder> {
        private final List<LocalLibrary> filteredLocalLibraries = new ArrayList<>();

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            var binding = ViewItemLocalLibSearchBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            return new ViewHolder(binding);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            var binding = holder.binding;
            var library = filteredLocalLibraries.get(position);

            binding.libraryName.setText(library.getName());
            binding.librarySize.setText(library.getSize());
            binding.libraryName.setSelected(true);

            binding.materialSwitch.setChecked(isUsedLibrary(library.getName()));
            if (!notAssociatedWithProject) {
                binding.materialSwitch.setEnabled(true);
                binding.getRoot().setOnClickListener(v -> binding.materialSwitch.performClick());

                binding.materialSwitch.setOnClickListener(v -> {
                    updateLibraryUsage(library, binding.materialSwitch.isChecked());
                    adapter.notifyDataSetChanged();
                });
            } else {
                binding.materialSwitch.setEnabled(false);
            }
        }

        @Override
        public int getItemCount() {
            return filteredLocalLibraries.size();
        }

        public void filter(List<LocalLibrary> localLibraries, String query) {
            filteredLocalLibraries.clear();
            if (query.isEmpty()) {
                filteredLocalLibraries.addAll(localLibraries);
            } else {
                for (LocalLibrary library : localLibraries) {
                    if (library.getName().toLowerCase().contains(query.toLowerCase())) {
                        filteredLocalLibraries.add(library);
                    }
                }
            }

            filteredLocalLibraries.sort((lib1, lib2) -> {
                boolean isEnabled1 = isUsedLibrary(lib1.getName());
                boolean isEnabled2 = isUsedLibrary(lib2.getName());
                return Boolean.compare(isEnabled2, isEnabled1);
            });

            notifyDataSetChanged();
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            private final ViewItemLocalLibSearchBinding binding;

            public ViewHolder(@NonNull ViewItemLocalLibSearchBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
