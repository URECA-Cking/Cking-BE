package kr.co.cking.subscriptionverification.infrastructure.deepseek;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisRequest;

/** DeepSeek Chat Completions API에 전달하는 Provider 전용 요청 DTO다. */
record DeepSeekChatCompletionRequest(
        String model,
        List<DeepSeekChatMessage> messages,
        Map<String, String> response_format,
        Map<String, String> thinking,
        int max_tokens) {

    /** 정규화 JPEG와 채널 정보를 DeepSeek Vision 요청 형식으로 변환한다. */
    static DeepSeekChatCompletionRequest from(
            VisionAnalysisRequest request,
            DeepSeekVisionAnalysisProperties properties) {
        String imageDataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(request.normalizedJpegBytes());
        return new DeepSeekChatCompletionRequest(
                properties.getModel(),
                List.of(
                        new DeepSeekChatMessage("system", DeepSeekSubscriptionAnalysisPrompt.render(request)),
                        new DeepSeekChatMessage("user", List.of(
                                DeepSeekContentPart.text("이미지와 대상 채널 정보를 분석해 JSON으로 반환하라."),
                                DeepSeekContentPart.image(imageDataUrl)))),
                Map.of("type", "json_object"),
                Map.of("type", "disabled"),
                Math.max(1, properties.getMaxOutputTokens()));
    }
}

/** DeepSeek 대화 한 건을 표현하는 Provider 전용 DTO다. */
record DeepSeekChatMessage(String role, Object content) {
}

/** 텍스트 또는 이미지로 구성되는 사용자 메시지의 content part DTO다. */
record DeepSeekContentPart(String type, String text, DeepSeekImageUrl image_url) {

    /** 텍스트 content part를 만든다. */
    static DeepSeekContentPart text(String text) {
        return new DeepSeekContentPart("text", text, null);
    }

    /** JPEG data URL을 담는 이미지 content part를 만든다. */
    static DeepSeekContentPart image(String imageDataUrl) {
        return new DeepSeekContentPart("image_url", null, new DeepSeekImageUrl(imageDataUrl, "high"));
    }
}

/** DeepSeek가 읽을 이미지 URL과 상세도를 표현하는 Provider 전용 DTO다. */
record DeepSeekImageUrl(String url, String detail) {
}
