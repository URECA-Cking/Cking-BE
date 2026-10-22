package kr.co.cking.quiz.grounding;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider가 돌려준 Grounding JSON 문자열을 결과 구조로만 바꾼다. 값의 의미 검증은 {@link VideoGroundingValidator}가
 * 맡는다. 형식이 틀리면 {@code INVALID_RESPONSE}이며 원문은 예외에 담지 않는다.
 *
 * <p>{@link GroundingPrompt#responseSchema()}처럼 root와 fact는 정의한 필드만 모두 가져야 한다. 필드가 빠지거나
 * 정의하지 않은 필드가 있으면 거부한다. 시각 필드는 키가 있어야 하며 값은 JSON null일 수 있다.
 */
public class GroundingResponseParser {

    private static final Set<String> ROOT_FIELDS = Set.of("contentText", "facts");
    private static final Set<String> FACT_FIELDS =
            Set.of("fact", "evidenceType", "evidence", "timestampStart", "timestampEnd");

    private final ObjectMapper objectMapper;

    public GroundingResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** promptVersion과 model은 실행 설정 값을 그대로 담는다. 응답에 같은 이름의 값이 있어도 쓰지 않는다. */
    public VideoGrounding parse(String json, String promptVersion, String model) {
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException | IllegalArgumentException exception) {
            throw invalid();
        }
        if (root == null || !root.keySet().equals(ROOT_FIELDS)
                || !(root.get("contentText") instanceof String contentText)
                || !(root.get("facts") instanceof List<?> rawFacts)) {
            throw invalid();
        }
        List<GroundingFact> facts = new ArrayList<>();
        for (Object rawFact : rawFacts) {
            if (!(rawFact instanceof Map<?, ?> fact) || !fact.keySet().equals(FACT_FIELDS)) {
                throw invalid();
            }
            facts.add(new GroundingFact(
                    requiredString(fact, "fact"),
                    evidenceType(fact.get("evidenceType")),
                    requiredString(fact, "evidence"),
                    optionalString(fact, "timestampStart"),
                    optionalString(fact, "timestampEnd")));
        }
        return new VideoGrounding(contentText, facts, promptVersion, model);
    }

    private String requiredString(Map<?, ?> object, String field) {
        if (!(object.get(field) instanceof String value)) {
            throw invalid();
        }
        return value;
    }

    private String optionalString(Map<?, ?> object, String field) {
        Object value = object.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string)) {
            throw invalid();
        }
        return string;
    }

    private EvidenceType evidenceType(Object value) {
        if (value instanceof String name) {
            for (EvidenceType type : EvidenceType.values()) {
                if (type.name().equals(name)) {
                    return type;
                }
            }
        }
        throw invalid();
    }

    private VideoGroundingException invalid() {
        return new VideoGroundingException(VideoGroundingException.Failure.INVALID_RESPONSE);
    }
}
