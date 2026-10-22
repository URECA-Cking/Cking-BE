package kr.co.cking.quiz.generation.gemini;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiApiResponseExtractorTest {

    private final GeminiApiResponseExtractor extractor =
            new GeminiApiResponseExtractor(new ObjectMapper());

    @Test
    void candidates_content_parts_text를_추출하고_json_code_fence를_제거한다() {
        String response = """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"```json\\n{\\\"questions\\\":[]}\\n```"}]}}]}
                """;

        assertThat(extractor.extractText(response)).isEqualTo("{\"questions\":[]}");
    }

    @Test
    void candidates가_없으면_Provider_오류다() {
        assertThatThrownBy(() -> extractor.extractText("{}"))
                .isInstanceOf(GeminiResponseException.class);
    }

    @Test
    void content_parts_text가_비어_있으면_Provider_오류다() {
        String response = """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"  "}]}}]}
                """;

        assertThatThrownBy(() -> extractor.extractText(response))
                .isInstanceOf(GeminiResponseException.class);
    }

    @Test
    void 잘못된_envelope_JSON은_Provider_오류다() {
        assertThatThrownBy(() -> extractor.extractText("not-json"))
                .isInstanceOf(GeminiResponseException.class);
    }

    @Test
    void null_envelope은_Provider_오류다() {
        assertThatThrownBy(() -> extractor.extractText("null"))
                .isInstanceOf(GeminiResponseException.class);
    }

    @Test
    void finishReason이_없으면_text가_있어도_거부한다() {
        assertThatThrownBy(() -> extractor.extractText(responseWithoutFinishReason()))
                .isInstanceOf(GeminiResponseException.class);
    }

    @Test
    void 비정상_finishReason은_text가_있어도_거부한다() {
        for (String reason : new String[] {"SAFETY", "RECITATION", "MAX_TOKENS", "OTHER"}) {
            assertThatThrownBy(() -> extractor.extractText(responseWithFinishReason(reason)))
                    .as(reason)
                    .isInstanceOf(GeminiResponseException.class);
        }
    }

    private String responseWithoutFinishReason() {
        return """
                {"candidates":[{"content":{"parts":[{"text":"{\\\"questions\\\":[]}"}]}}]}
                """;
    }

    private String responseWithFinishReason(String reason) {
        return """
                {"candidates":[{"finishReason":"%s","content":{"parts":[{"text":"{\\\"questions\\\":[]}"}]}}]}
                """.formatted(reason);
    }
}
