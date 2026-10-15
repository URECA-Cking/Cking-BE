package kr.co.cking.quiz.grounding;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroundingResponseParserTest {

    private final GroundingResponseParser parser = new GroundingResponseParser(new ObjectMapper());

    @Test
    void 응답을_결과로_바꾸고_promptVersion과_model은_실행_설정_값을_쓴다() {
        String json = """
                {"contentText": "물은 증발해 구름이 된다.",
                 "facts": [{"fact": "물은 증발한다.", "evidenceType": "VISUAL", "evidence": "증발 장면",
                            "timestampStart": "00:10", "timestampEnd": "00:12.5"}]}
                """;

        VideoGrounding grounding = parser.parse(json, GroundingPrompt.VERSION, "gemini-test");

        assertThat(grounding.contentText()).isEqualTo("물은 증발해 구름이 된다.");
        assertThat(grounding.promptVersion()).isEqualTo("quiz-grounding-v1");
        assertThat(grounding.model()).isEqualTo("gemini-test");
        assertThat(grounding.facts()).containsExactly(
                new GroundingFact("물은 증발한다.", EvidenceType.VISUAL, "증발 장면", "00:10", "00:12.5"));
    }

    @Test
    void 시각_키가_있고_값이_null이면_허용한다() {
        String json = """
                {"contentText": "본문", "facts": [{"fact": "사실", "evidenceType": "UNKNOWN", "evidence": "근거",
                                                  "timestampStart": null, "timestampEnd": null}]}
                """;

        assertThat(parser.parse(json, GroundingPrompt.VERSION, "gemini-test").facts()).containsExactly(
                new GroundingFact("사실", EvidenceType.UNKNOWN, "근거", null, null));
    }

    @Test
    void 시각_키가_없으면_거부한다() {
        assertInvalid("""
                {"contentText": "본문", "facts": [{"fact": "사실", "evidenceType": "SPEECH", "evidence": "근거",
                                                  "timestampEnd": null}]}""");
        assertInvalid("""
                {"contentText": "본문", "facts": [{"fact": "사실", "evidenceType": "SPEECH", "evidence": "근거",
                                                  "timestampStart": null}]}""");
    }

    @Test
    void schema에_없는_필드가_있으면_거부한다() {
        assertInvalid("""
                {"contentText": "본문", "facts": [], "promptVersion": "provider-version"}""");
        assertInvalid("""
                {"contentText": "본문", "facts": [], "model": "provider-model"}""");
        assertInvalid("""
                {"contentText": "본문", "facts": [{"fact": "사실", "evidenceType": "SPEECH", "evidence": "근거",
                                                  "timestampStart": null, "timestampEnd": null, "note": "extra"}]}""");
    }

    @Test
    void 허용_필드는_응답_schema와_같다() {
        Map<?, ?> rootProperties = (Map<?, ?>) GroundingPrompt.responseSchema().get("properties");
        Map<?, ?> factSchema = (Map<?, ?>) ((Map<?, ?>) rootProperties.get("facts")).get("items");
        String root = """
                {"contentText": "본문", "facts": [{"fact": "사실", "evidenceType": "SPEECH", "evidence": "근거",
                                                  "timestampStart": null, "timestampEnd": null}]}""";

        assertThat(Set.copyOf(rootProperties.keySet())).isEqualTo(Set.of("contentText", "facts"));
        assertThat(Set.copyOf(((Map<?, ?>) factSchema.get("properties")).keySet()))
                .isEqualTo(Set.of("fact", "evidenceType", "evidence", "timestampStart", "timestampEnd"));
        assertThat(Set.copyOf((List<?>) GroundingPrompt.responseSchema().get("required")))
                .isEqualTo(Set.of("contentText", "facts"));
        assertThat(Set.copyOf((List<?>) factSchema.get("required")))
                .isEqualTo(Set.of("fact", "evidenceType", "evidence", "timestampStart", "timestampEnd"));
        assertThat(parser.parse(root, GroundingPrompt.VERSION, "gemini-test").facts()).hasSize(1);
    }

    @Test
    void 형식이_맞지_않는_응답은_거부한다() {
        String[] invalid = {
                "not json",
                "[]",
                "null",
                "{\"facts\": []}",
                "{\"contentText\": 1, \"facts\": []}",
                "{\"contentText\": \"본문\"}",
                "{\"contentText\": \"본문\", \"facts\": null}",
                "{\"contentText\": \"본문\", \"facts\": [\"사실\"]}",
                fact("\"evidenceType\": \"SPEECH\", \"evidence\": \"근거\""),
                fact("\"fact\": 1, \"evidenceType\": \"SPEECH\", \"evidence\": \"근거\""),
                fact("\"fact\": \"사실\", \"evidenceType\": \"speech\", \"evidence\": \"근거\""),
                fact("\"fact\": \"사실\", \"evidenceType\": \"OTHER\", \"evidence\": \"근거\""),
                fact("\"fact\": \"사실\", \"evidenceType\": null, \"evidence\": \"근거\""),
                fact("\"fact\": \"사실\", \"evidenceType\": \"SPEECH\", \"evidence\": null"),
                "{\"contentText\": \"본문\", \"facts\": [{\"fact\": \"사실\", \"evidenceType\": \"SPEECH\", "
                        + "\"evidence\": \"근거\", \"timestampStart\": 10, \"timestampEnd\": null}]}"
        };
        for (String json : invalid) {
            assertInvalid(json);
        }
    }

    /** 시각 키는 모두 갖춘 fact. 시각 외 필드의 형식 오류만 확인한다. */
    private String fact(String fields) {
        return "{\"contentText\": \"본문\", \"facts\": [{" + fields
                + ", \"timestampStart\": null, \"timestampEnd\": null}]}";
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> parser.parse(json, GroundingPrompt.VERSION, "gemini-test"))
                .as(json)
                .isInstanceOfSatisfying(VideoGroundingException.class, exception -> {
                    assertThat(exception.getFailure()).isEqualTo(VideoGroundingException.Failure.INVALID_RESPONSE);
                    assertThat(exception).hasNoCause();
                });
    }
}
