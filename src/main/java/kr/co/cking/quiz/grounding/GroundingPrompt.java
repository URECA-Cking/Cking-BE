package kr.co.cking.quiz.grounding;

import java.util.List;
import java.util.Map;

/**
 * BE가 관리하는 영상 Grounding prompt와 응답 JSON schema. 문구나 schema를 바꾸면 {@link #VERSION}을 올린다.
 *
 * <p>Cking-Quiz-Benchmark의 {@code video-grounding-v3}(한국어 contentText 규칙, MM:SS 시각)를 참고했지만 별도로
 * 관리한다. 시각 형식은 schema로 강제할 수 없어 {@link VideoGroundingValidator}가 검사한다.
 */
public final class GroundingPrompt {

    public static final String VERSION = "quiz-grounding-v1";

    private static final String TEXT = """
            영상에서 확인할 수 있는 사실을 추출하고, 그 사실들로 퀴즈 생성용 contentText를 작성하세요. 추측과 영상 밖의 지식은 쓰지 마세요.

            facts 작성 규칙:
            - fact에는 영상에서 확인한 사실 하나를, evidence에는 그 근거가 된 발화나 화면 내용을 적으세요.
            - evidenceType은 주된 근거가 발화·나레이션이면 SPEECH, 화면의 시각 정보면 VISUAL로 쓰세요. 발화와 화면 양쪽에 근거가 있으면 주된 근거를 고르고, 어느 쪽인지 판단할 수 없을 때만 UNKNOWN을 쓰세요.
            - timestampStart와 timestampEnd는 영상 시작부터의 시각을 "MM:SS" 또는 "MM:SS.s" 문자열로 쓰세요. 예: 1분 41.2초는 "01:41.2". 초는 00~59로 쓰고 초 단위 숫자로 바꾸지 마세요.
            - timestampStart는 timestampEnd보다 늦을 수 없습니다. 근거 위치를 특정할 수 없으면 두 값을 모두 null로 쓰세요.

            contentText 작성 규칙:
            1) contentText는 서로 다른 퀴즈 문제 여러 개의 유일한 입력 자료입니다. 영상의 핵심 정보를 빠뜨리지 말고 충분히 담으세요.
            2) 영상에서 확인되지 않는 사실이나 외부 지식으로 보충한 내용을 넣지 마세요.
            3) 숫자와 단위, 고유명사, 인과관계 같은 구체적인 정보를 일반적인 표현으로 바꾸지 말고 그대로 남기세요.
            4) 서로 다른 문제의 근거가 될 수 있도록 서로 구별되는 정보를 각각 담으세요.
            5) 한국어로 쓰고 50,000자를 넘기지 마세요.
            6) facts와 모순되는 내용을 쓰지 마세요.
            7) "이 영상은 ~을 설명합니다"처럼 요약·소개만 하는 문장을 넣지 마세요.
            """;

    private GroundingPrompt() {
    }

    public static String text() {
        return TEXT;
    }

    /** contentText와 facts를 요구하는 응답 schema. */
    public static Map<String, Object> responseSchema() {
        Map<String, Object> string = Map.of("type", "string");
        Map<String, Object> timestamp = Map.of(
                "type", List.of("string", "null"),
                "description", "영상 시작부터의 \"MM:SS\" 또는 \"MM:SS.s\" 시각. 특정할 수 없으면 null");
        Map<String, Object> fact = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "fact", string,
                        "evidenceType", Map.of("type", "string", "enum", List.of("SPEECH", "VISUAL", "UNKNOWN")),
                        "evidence", string,
                        "timestampStart", timestamp,
                        "timestampEnd", timestamp),
                "required", List.of("fact", "evidenceType", "evidence", "timestampStart", "timestampEnd"));
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "contentText", string,
                        "facts", Map.of("type", "array", "items", fact)),
                "required", List.of("contentText", "facts"));
    }
}
