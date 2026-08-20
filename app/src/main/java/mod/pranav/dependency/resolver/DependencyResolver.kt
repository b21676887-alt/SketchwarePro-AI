package mod.pranav.dependency.resolver

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.GlobalSyntheticsConsumer
import com.android.tools.r8.OutputMode
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import mod.hey.studios.build.BuildSettings
import mod.hey.studios.util.Helper
import mod.jbk.build.BuiltInLibraries
import mod.jbk.build.compiler.resource.LibraryResourceSanitizer
import org.cosmic.ide.dependency.resolver.api.Artifact
import org.cosmic.ide.dependency.resolver.api.EventReciever
import org.cosmic.ide.dependency.resolver.api.Repository
import org.cosmic.ide.dependency.resolver.eventReciever
import org.cosmic.ide.dependency.resolver.getArtifact
import org.cosmic.ide.dependency.resolver.repositories
import pro.sketchware.utility.FileUtil
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import mod.hey.studios.project.ProjectSettings;

class DependencyResolver(
    private val groupId: String,
    private val artifactId: String,
    private val version: String,
    private val skipDependencies: Boolean,
    private val buildSettings: BuildSettings
) {
    companion object {
        private val DEFAULT_REPOS = """
          |[
          |    {"url": "https://dl.google.com/dl/android/maven2", "name": "Google"},
          |    {"url": "https://repo.maven.apache.org/maven2", "name": "Apache Maven"},
          |    {"url": "https://jitpack.io", "name": "JitPack"},
          |    {"url": "https://repo.hortonworks.com/content/repositories/releases", "name": "HortanWorks"},
          |    {"url": "https://maven.atlassian.com/content/repositories/atlassian-public", "name": "Atlassian"},
          |    {"url": "https://jcenter.bintray.com", "name": "JCenter"},
          |    {"url": "https://oss.sonatype.org/content/repositories/releases", "name": "Sonatype"},
          |    {"url": "https://repo.spring.io/plugins-release", "name": "Spring Plugins"},
          |    {"url": "https://repo.spring.io/libs-milestone", "name": "Spring Milestone"}
          |]
        """.trimMargin()

        private val syntheticCounter = AtomicInteger(0)
        private const val MAX_CHUNK_SIZE_BYTES = 9 * 1024 * 1024L
        private const val MIN_CHUNK_SIZE_BYTES = 2 * 1024 * 1024L
        private const val MAX_JAR_SIZE_BYTES = 12 * 1024 * 1024L
    }

    private val downloadPath: String =
        FileUtil.getExternalStorageDir() + "/.sketchware/libs/local_libs"

    private val repositoriesJson = Paths.get(
        FileUtil.getExternalStorageDir(),
        ".sketchware",
        "libs",
        "repositories.json"
    )

    init {
        if (Files.notExists(repositoriesJson)) {
            repositoriesJson.parent?.let { Files.createDirectories(it) }
            repositoriesJson.writeText(DEFAULT_REPOS)
        }
        Gson().fromJson(repositoriesJson.readText(), Helper.TYPE_MAP_LIST).forEach {
            val url: String? = it["url"] as String?
            if (url != null) {
                repositories.add(object : Repository {
                    override fun getName(): String {
                        return it["name"] as String
                    }

                    override fun getURL(): String {
                        return if (url.endsWith("/")) {
                            url.substringBeforeLast("/")
                        } else {
                            url
                        }
                    }
                })
            }
        }
    }

    open class DependencyResolverCallback : EventReciever() {
        override fun artifactFound(artifact: Artifact) {}
        override fun onArtifactNotFound(artifact: Artifact) {}
        override fun onFetchingLatestVersion(artifact: Artifact) {}
        override fun onFetchedLatestVersion(artifact: Artifact, version: String) {}
        override fun onResolving(artifact: Artifact, dependency: Artifact) {}
        override fun onResolutionComplete(artifact: Artifact) {}
        override fun onSkippingResolution(artifact: Artifact) {}
        override fun onVersionNotFound(artifact: Artifact) {}
        override fun onDependenciesNotFound(artifact: Artifact) {}
        override fun onInvalidScope(artifact: Artifact, scope: String) {}
        override fun onInvalidPOM(artifact: Artifact) {}
        override fun onDownloadStart(artifact: Artifact) {}
        override fun onDownloadEnd(artifact: Artifact) {}
        override fun onDownloadError(artifact: Artifact, error: Throwable) {}
        open fun unzipping(artifact: Artifact) {}
        open fun dexing(artifact: Artifact) {}
        open fun onTaskCompleted(artifacts: List<String>) {}
        open fun dexingFailed(artifact: Artifact, e: Exception) {}
        open fun invalidPackaging(artifact: Artifact) {}
    }

    fun resolveDependency(callback: DependencyResolverCallback) = runBlocking {
        eventReciever = callback
        val dependency = getArtifact(groupId, artifactId, version) ?: return@runBlocking

        if (dependency.extension != "jar" && dependency.extension != "aar") {
            callback.invalidPackaging(dependency)
            return@runBlocking
        }

        val libraryJars = listOf(
            BuiltInLibraries.EXTRACTED_COMPILE_ASSETS_PATH.toPath()
                .resolve("core-lambda-stubs.jar"), Paths.get(
                buildSettings.getValue(
                    BuildSettings.SETTING_ANDROID_JAR_PATH,
                    BuiltInLibraries.EXTRACTED_COMPILE_ASSETS_PATH.resolve("android.jar").absolutePath
                )
            )
        )
        val dependencyClasspath = mutableListOf<Path>()

        val classpath = buildSettings.getValue(BuildSettings.SETTING_CLASSPATH, "")

        classpath.split(":").forEach {
            if (it.isEmpty()) return@forEach
            dependencyClasspath.add(Paths.get(it))
        }

        val mainArtifactPath = Paths.get(
            downloadPath,
            "${dependency.artifactId}-v${dependency.version}",
            "classes.${dependency.extension}"
        )

        if (!downloadArtifact(dependency, mainArtifactPath, callback)) {
            return@runBlocking
        }

        if (dependency.extension == "aar") {
            callback.unzipping(dependency)
            if (!unzipArtifact(dependency, mainArtifactPath, callback)) {
                return@runBlocking
            }
            LibraryResourceSanitizer.sanitizeResourceDirectory(
                Paths.get(downloadPath, "${dependency.artifactId}-v${dependency.version}", "res").toFile()
            )
            Files.deleteIfExists(mainArtifactPath)
            val packageName = findPackageName(
                Paths.get(downloadPath, "${dependency.artifactId}-v${dependency.version}")
                    .toAbsolutePath().toString(),
                dependency.groupId
            )
            Paths.get(downloadPath, "${dependency.artifactId}-v${dependency.version}", "config")
                .writeText(packageName)
        }

        val jar = Paths.get(
            downloadPath,
            "${dependency.artifactId}-v${dependency.version}",
            "classes.jar"
        )

        callback.dexing(dependency)
        try {
            compileJar(jar, dependencyClasspath, libraryJars)
            callback.onResolutionComplete(dependency)
        } catch (e: Exception) {
            callback.dexingFailed(dependency, e)
        }

        if (skipDependencies) {
            callback.onSkippingResolution(dependency)
            callback.onTaskCompleted(listOf("${dependency.artifactId}-v${dependency.version}"))
            return@runBlocking
        }
        dependency.resolveDependencyTree()

        var dependencyResolutionFailed = false
        dependency.getAllDependencies().forEach { dep ->
            if (dependencyResolutionFailed) return@forEach
            println("Resolving dependency: ${dep.artifactId} v${dep.version}")
            if (dep.extension != "jar" && dep.extension != "aar") {
                callback.invalidPackaging(dep)
                return@forEach
            }

            if (dep.version.isEmpty()) {
                callback.onVersionNotFound(dep)
                return@forEach
            }

            val path = Paths.get(
                downloadPath,
                "${dep.artifactId}-v${dep.version}",
                "classes.${dep.extension}"
            )

            if (!downloadArtifact(dep, path, callback)) {
                dependencyResolutionFailed = true
                return@forEach
            }

            if (dep.extension == "aar") {
                callback.unzipping(dep)
                if (!unzipArtifact(dep, path, callback)) {
                    dependencyResolutionFailed = true
                    return@forEach
                }
                LibraryResourceSanitizer.sanitizeResourceDirectory(path.parent.resolve("res").toFile())
                Files.deleteIfExists(path)
                val packageName =
                    findPackageName(path.parent.toAbsolutePath().toString(), dep.groupId)
                path.parent.resolve("config").writeText(packageName)
            }

            val jar = if (dep.extension == "jar") path else Paths.get(
                downloadPath, "${dep.artifactId}-v${dep.version}", "classes.jar"
            )
            if (Files.notExists(jar)) {
                callback.onDependenciesNotFound(dep)
                return@forEach
            }

            dependencyClasspath.add(jar)
        }

        if (dependencyResolutionFailed) {
            return@runBlocking
        }

        dependency.getAllDependencies().forEach { dep ->
            val jar = Paths.get(downloadPath, "${dep.artifactId}-v${dep.version}", "classes.jar")

            callback.dexing(dep)
            try {
                compileJar(
                    jar, dependencyClasspath.toMutableList().apply { remove(jar) }, libraryJars
                )
                callback.onResolutionComplete(dep)
            } catch (e: Exception) {
                callback.dexingFailed(dep, e)
                return@forEach
            }
        }

        callback.onTaskCompleted(
            dependency.getAllDependencies().map { "${it.artifactId}-v${it.version}" })
    }

    private fun findPackageName(path: String, defaultValue: String): String {
        val manifest =
            File(path).walk().filter { it.isFile && it.name == "AndroidManifest.xml" }.firstOrNull()
        val content = manifest?.readText() ?: return defaultValue
        val p = Pattern.compile("<manifest.*package=\"(.*?)\"", Pattern.DOTALL)
        val m = p.matcher(content)
        if (m.find()) {
            return m.group(1)!!
        }

        return defaultValue
    }

    private fun downloadArtifact(
        artifact: Artifact,
        path: Path,
        callback: DependencyResolverCallback
    ): Boolean {
        return try {
            path.parent?.let { Files.createDirectories(it) }
            artifact.downloadTo(path.toFile())
            if (Files.notExists(path) || Files.size(path) == 0L) {
                throw IllegalStateException("Downloaded file is empty")
            }
            true
        } catch (e: Throwable) {
            Files.deleteIfExists(path)
            callback.onDownloadError(artifact, e)
            false
        }
    }

    private fun unzipArtifact(
        artifact: Artifact,
        path: Path,
        callback: DependencyResolverCallback
    ): Boolean {
        return try {
            unzip(path)
            true
        } catch (e: Throwable) {
            Files.deleteIfExists(path)
            callback.onDownloadError(
                artifact,
                IllegalStateException(
                    "Downloaded ${artifact.extension} is not a valid ZIP archive. Try downloading the dependency again.",
                    e
                )
            )
            false
        }
    }

    private fun unzip(path: Path) {
        val targetDir = path.parent.normalize()
        ZipFile(path.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val entryDestination = targetDir.resolve(entry.name).normalize()

                if (!entryDestination.startsWith(targetDir)) {
                    throw SecurityException("Bad zip entry path: ${entry.name}")
                }

                if (entry.isDirectory) {
                    Files.createDirectories(entryDestination)
                } else {
                    entryDestination.parent?.let { Files.createDirectories(it) }
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(entryDestination).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun splitJarFile(jarFile: File): List<File> {
        val splitJars = mutableListOf<File>()
        if (!jarFile.exists()) return splitJars
        if (jarFile.length() <= MAX_JAR_SIZE_BYTES) {
            splitJars.add(jarFile)
            return splitJars
        }

        val classEntries = mutableListOf<Pair<String, ByteArray>>()
        val resourceEntries = mutableListOf<Pair<String, ByteArray>>()
        val addedClassNames = mutableSetOf<String>()

        ZipInputStream(FileInputStream(jarFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    if (entry.name.endsWith(".class")) {
                        if (addedClassNames.add(entry.name)) {
                            classEntries.add(entry.name to bytes)
                        }
                    } else {
                        resourceEntries.add(entry.name to bytes)
                    }
                }
                entry = zis.nextEntry
            }
        }

        val chunks = mutableListOf<MutableList<Pair<String, ByteArray>>>()
        var currentChunk = mutableListOf<Pair<String, ByteArray>>()
        var currentChunkSize = 0L

        for (item in classEntries) {
            val itemSize = item.second.size.toLong()
            if (currentChunk.isNotEmpty() && (currentChunkSize + itemSize > MAX_CHUNK_SIZE_BYTES)) {
                chunks.add(currentChunk)
                currentChunk = mutableListOf()
                currentChunkSize = 0L
            }
            currentChunk.add(item)
            currentChunkSize += itemSize
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }

        if (chunks.size > 1) {
            val lastChunk = chunks.last()
            val lastChunkTotalBytes = lastChunk.sumOf { it.second.size.toLong() }

            if (lastChunkTotalBytes <= MIN_CHUNK_SIZE_BYTES) {
                val previousChunk = chunks[chunks.size - 2]
                previousChunk.addAll(lastChunk)
                chunks.removeAt(chunks.size - 1)
            }
        }

        chunks.forEachIndexed { index, chunkClasses ->
            val partIndex = index + 1
            val chunkFile = File(jarFile.parentFile, "split_${partIndex}_${jarFile.name}")

            ZipOutputStream(FileOutputStream(chunkFile)).use { zos ->
                for ((name, bytes) in chunkClasses) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }

                if (partIndex == 1) {
                    for ((name, bytes) in resourceEntries) {
                        zos.putNextEntry(java.util.zip.ZipEntry(name))
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                }
            }
            splitJars.add(chunkFile)
        }

        return if (splitJars.isEmpty()) listOf(jarFile) else splitJars
    }

    private fun createGlobalSyntheticsConsumer(outputDir: File): GlobalSyntheticsConsumer {
        return GlobalSyntheticsConsumer { provider, _, _ ->
            try {
                val bytes = provider.buffer
                if (bytes != null && bytes.isNotEmpty()) {
                    val synthFile = File(outputDir, "synthetic_${syntheticCounter.incrementAndGet()}.dex")
                    FileOutputStream(synthFile).use { fos ->
                        fos.write(bytes, provider.offset, provider.length)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun cleanupSyntheticFiles(targetDir: File) {
        runCatching {
            targetDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.startsWith("synthetic_") || file.name.endsWith(".synthetic"))) {
                    file.delete()
                }
            }
        }
    }

    private fun compileJar(jarFile: Path, jars: List<Path>, libraryJars: List<Path>) {
        jarFile.parent?.let { Files.createDirectories(it) }
        val minApi = buildSettings.getValue(ProjectSettings.SETTING_MINIMUM_SDK_VERSION, "21").toIntOrNull() ?: 21
        val targetDir = jarFile.parent.toFile()

        val jarChunks = splitJarFile(jarFile.toFile()).map { it.toPath() }
        val syntheticsConsumer = createGlobalSyntheticsConsumer(targetDir)

        val isChunked = jarChunks.size > 1

        try {
            if (isChunked) {
                val tempDexFiles = mutableListOf<File>()

                jarChunks.forEachIndexed { index, chunk ->
                    val tempChunkDir = File(targetDir, "temp_dex_$index")
                    tempChunkDir.mkdirs()

                    val otherChunksAsClasspath = jarChunks.filter { it != chunk }
                    val combinedClasspath = (jars + otherChunksAsClasspath).distinct()

                    val builder = D8Command.builder()
                        .setIntermediate(true)
                        .setMode(CompilationMode.RELEASE)
                        .setMinApiLevel(minApi)
                        .addProgramFiles(chunk)
                        .addLibraryFiles(libraryJars)
                        .addClasspathFiles(combinedClasspath)
                        .setGlobalSyntheticsConsumer(syntheticsConsumer)
                        .setOutput(tempChunkDir.toPath(), OutputMode.DexIndexed)

                    D8.run(builder.build())

                    tempChunkDir.listFiles { _, name -> name.endsWith(".dex") }?.let { dexes ->
                        dexes.sortBy { it.name }
                        tempDexFiles.addAll(dexes)
                    }
                }

                tempDexFiles.forEachIndexed { index, dexFile ->
                    val newName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                    val destFile = File(targetDir, newName)
                    if (destFile.exists()) destFile.delete()
                    dexFile.renameTo(destFile)
                }

                for (i in jarChunks.indices) {
                    File(targetDir, "temp_dex_$i").deleteRecursively()
                }

            } else {
                val chunk = jarChunks.first()
                val builder = D8Command.builder()
                    .setIntermediate(true)
                    .setMode(CompilationMode.RELEASE)
                    .setMinApiLevel(minApi)
                    .addProgramFiles(chunk)
                    .addLibraryFiles(libraryJars)
                    .addClasspathFiles(jars)
                    .setGlobalSyntheticsConsumer(syntheticsConsumer)
                    .setOutput(jarFile.parent, OutputMode.DexIndexed)

                D8.run(builder.build())
            }
        } finally {
            jarChunks.forEach { chunk ->
                if (chunk != jarFile && Files.exists(chunk)) {
                    runCatching { Files.delete(chunk) }
                }
            }
            cleanupSyntheticFiles(targetDir)
            System.gc()
        }
    }
}
