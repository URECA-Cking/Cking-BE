package kr.co.cking.subscriptionverification.infrastructure.gemini;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisRequest;

/** Gemini generateContent API에 전달하는 Provider 전용 요청 DTO다. */
record GeminiGenerateContentRequest(
        List<GeminiContent> contents,
        Map<String, Object> generationConfig) {

    static GeminiGenerateContentRequest from(
            VisionAnalysisRequest request,
            GeminiVisionAnalysisProperties properties) {
        String imageBase64 = Base64.getEncoder().encodeToString(request.normalizedJpegBytes());
        List<GeminiPart> parts = List.of(
                GeminiPart.text(GeminiSubscriptionAnalysisPrompt.render(request)),
                GeminiPart.image(imageBase64));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseFormat", Map.of("text", Map.of(
                "mimeType", "application/json",
                "schema", GeminiVisionSchemaFactory.analysisSchema())));
        generationConfig.put("thinkingConfig", Map.of("thinkingLevel", "minimal"));
        generationConfig.put("maxOutputTokens", Math.max(1, properties.getMaxOutputTokens()));
        return new GeminiGenerateContentRequest(List.of(new GeminiContent("user", parts)), generationConfig);
    }
}

record GeminiContent(String role, List<GeminiPart> parts) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record GeminiPart(String text, GeminiInlineData inlineData) {

    static GeminiPart text(String text) {
        return new GeminiPart(text, null);
    }

    static GeminiPart image(String imageBase64) {
        return new GeminiPart(null, new GeminiInlineData("image/jpeg", imageBase64));
    }
}

record GeminiInlineData(String mimeType, String data) {
}
