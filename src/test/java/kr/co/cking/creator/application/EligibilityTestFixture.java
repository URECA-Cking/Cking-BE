package kr.co.cking.creator.application;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 테스트가 명시한 분류만 사용한다. 실제 모델의 품질을 검증하는 fixture는 아니다. */
final class EligibilityTestFixture {
    private EligibilityTestFixture() { }

    static Map<String, Object> payload(JdbcTemplate jdbc, String version, Collection<Long> ids, String... tags) {
        var profiles = new TreeSet<>(ids).stream().map(id -> {
            var texts = jdbc.queryForList("SELECT intro_text FROM creator_space WHERE creator_id=?", String.class, id);
            return Map.<String,Object>of("creatorId", id, "introductionHash",
                    RecommendationEligibilityService.introductionHash(texts.isEmpty() ? "" : texts.getFirst()),
                    "tags", new TreeSet<>(List.of(tags)));
        }).toList();
        return Map.of("policyVersion", "RELATED_V1", "taxonomyVersion", version,
                "taxonomyHash", jdbc.queryForObject("SELECT taxonomy_hash FROM interest_taxonomy WHERE taxonomy_version=?", String.class, version),
                "profiles", profiles);
    }

    static JsonNode proof(JdbcTemplate jdbc, String version, Collection<Long> ids, String... tags) {
        return new ObjectMapper().valueToTree(payload(jdbc, version, ids, tags));
    }

    /** SQL로 세대를 준비하는 기존 정책 테스트에 분류 근거도 명시한다. */
    static void mark(JdbcTemplate jdbc, String kind, long generation, String version, Collection<Long> ids, String... tags) {
        jdbc.update("INSERT INTO recommendation_eligibility(source_kind,generation_id,proof) VALUES(?,?,?)",
                kind, generation, proof(jdbc, version, ids, tags).toString());
    }
}
