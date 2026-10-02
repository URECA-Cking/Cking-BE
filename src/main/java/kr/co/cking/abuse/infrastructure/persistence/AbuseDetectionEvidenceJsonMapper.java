package kr.co.cking.abuse.infrastructure.persistence;

import kr.co.cking.abuse.domain.DetectionEvidence;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** DetectionEvidence와 MySQL JSON 컬럼 문자열을 상호 변환한다. */
@Component
class AbuseDetectionEvidenceJsonMapper {

    private final ObjectMapper objectMapper;

    /** Spring이 설정한 ObjectMapper를 받아 Evidence JSON 형식을 일관되게 처리한다. */
    AbuseDetectionEvidenceJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Domain Evidence를 MySQL JSON 컬럼에 저장할 문자열로 직렬화한다. */
    String toJson(DetectionEvidence evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JacksonException exception) {
            throw new IllegalStateException("DetectionEvidence를 JSON으로 직렬화할 수 없습니다.", exception);
        }
    }

    /** MySQL JSON 컬럼 문자열을 검증된 Domain Evidence로 역직렬화한다. */
    DetectionEvidence fromJson(String evidenceJson) {
        try {
            return objectMapper.readValue(evidenceJson, DetectionEvidence.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("DetectionEvidence JSON을 복원할 수 없습니다.", exception);
        }
    }
}
