package pro.sketchware.ia;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;

import pro.sketchware.SketchApplication;
import pro.sketchware.network.AiProviderService;

public final class GeradorDeLogic {
    private static final String TAG = "GeradorDeLogic";
    private final String request;

    public GeradorDeLogic(String request) {
        this.request = request == null ? "" : request.trim();
    }

    public String gerarCode() throws IOException {
        Context context = SketchApplication.getContext();
        LayoutGeneratorModelSelector.SelectedModel selectedModel =
                LayoutGeneratorModelSelector.getCurrentChatModel(context);

        Log.d(TAG, "Generating logic code using model: " + selectedModel);

        String systemPrompt = "You are a professional Android Java and Sketchware developer. "
                + "Generate valid Java code based on the user's request. "
                + "CRITICAL INSTRUCTION: Output ONLY raw Java code inside a standard code block ```java ... ``` or as plain code. "
                + "Do NOT include any explanations, markdown headers, or introductory text outside the code block.";

        String userPrompt = "User request:\n" + request;

        String response = AiProviderService.getInstance().sendTextMessage(
                selectedModel.providerId,
                selectedModel.modelName,
                systemPrompt,
                userPrompt,
                new ArrayList<>()
        );

        return extractPureCode(response);
    }

    private String extractPureCode(String response) {
        if (response == null) return "";
        String code = response.trim();
        if (code.contains("```java")) {
            code = code.substring(code.indexOf("```java") + 7);
            if (code.contains("```")) {
                code = code.substring(0, code.indexOf("```"));
            }
        } else if (code.contains("```")) {
            code = code.substring(code.indexOf("```") + 3);
            if (code.contains("```")) {
                code = code.substring(0, code.indexOf("```"));
            }
        }
        return code.trim();
    }
}
