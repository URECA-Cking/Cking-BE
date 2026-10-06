package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.co.cking.creator.application.PersonalizedCreatorRecommendationPolicy.Recommendation;
import kr.co.cking.creator.repository.ActiveCreatorRecommendationCandidate;
import kr.co.cking.interest.repository.ActiveInterestRecommendationCandidate;
import org.junit.jupiter.api.Test;

/**
 * Cking-LLM-Benchmark의 공용 fixture(`fixtures/hybrid_personalized_v1.json`, 커밋 c23f4db)를 그대로 복사해 모든 case의
 * 결과 객체 전체(정책 버전·유효 source 수·후보 순서·점수 문자열·기여 출처)가 Python 참조 구현과 같은지 확인한다.
 *
 * <p>fixture 입력의 "Space 없는 Creator"는 운영에서는 조회 SQL이 미리 걸러내므로, 여기서는 제외 대상에 합쳐 같은 효과를 낸다.
 */
class PersonalizedCreatorRecommendationPolicyFixtureTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/hybrid/hybrid_personalized_v1.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PersonalizedCreatorRecommendationPolicy policy = new PersonalizedCreatorRecommendationPolicy();

    @Test
    void fixture_정책_설정이_구현_상수와_같다() throws IOException {
        JsonNode policyNode = MAPPER.readTree(Files.readString(FIXTURE)).get("policy");

        assertThat(policyNode.get("rrfK").asInt()).isEqualTo(60);
        assertThat(policyNode.get("scoreScale").asInt()).isEqualTo(8);
        assertThat(policyNode.get("roundingMode").asText()).isEqualTo("HALF_UP");
        assertThat(policyNode.get("interestWeight").asText()).isEqualTo("0.5");
        assertThat(policyNode.get("followWeight").asText()).isEqualTo("0.5");
    }

    @Test
    void 모든_case의_결과가_fixture와_정확히_같다() throws IOException {
        JsonNode cases = MAPPER.readTree(Files.readString(FIXTURE)).get("cases");

        assertThat(cases).hasSize(18);
        for (JsonNode fixtureCase : cases) {
            // 숫자 노드 타입(int/long) 차이로 어긋나지 않도록 직렬화한 JSON을 다시 파싱해 fixture와 같은 타입으로 맞춘다.
            assertThat(MAPPER.readTree(run(fixtureCase.get("input")).toString()))
                    .as("case %s", fixtureCase.get("id").asText())
                    .isEqualTo(fixtureCase.get("expected"));
        }
    }

    private ObjectNode run(JsonNode input) {
        Set<Long> followed = new HashSet<>();
        input.get("followedCreatorIds").forEach(id -> followed.add(id.asLong()));
        Set<String> selectedInterests = new HashSet<>();
        input.get("selectedInterestCodes").forEach(code -> selectedInterests.add(code.asText()));

        Set<Long> excluded = new HashSet<>(followed);
        JsonNode memberCreatorId = input.get("memberCreatorId");
        if (memberCreatorId != null && !memberCreatorId.isNull()) {
            excluded.add(memberCreatorId.asLong());
        }
        // 계약: creatorSpaces에 없는 ID도 Space 없음이다. 운영에서는 조회 SQL이 Space 없는 후보를 미리 걸러낸다.
        Set<Long> withSpace = new HashSet<>();
        input.get("creatorSpaces").forEach(space -> {
            if (space.get("hasCreatorSpace").asBoolean()) {
                withSpace.add(space.get("creatorId").asLong());
            }
        });

        List<ActiveInterestRecommendationCandidate> interestRows = new ArrayList<>();
        for (JsonNode source : input.get("interestSources")) {
            String code = source.get("interestCode").asText();
            if (selectedInterests.contains(code) && !source.get("activeGeneration").isNull()) {
                source.get("activeGeneration").get("candidates").forEach(candidate -> interestRows.add(
                        new ActiveInterestRecommendationCandidate(
                                code, candidate.get("creatorId").asLong(), candidate.get("rank").asInt())));
            }
        }
        List<ActiveCreatorRecommendationCandidate> followRows = new ArrayList<>();
        for (JsonNode source : input.get("followSources")) {
            long seed = source.get("seedCreatorId").asLong();
            if (followed.contains(seed) && !source.get("activeGeneration").isNull()) {
                source.get("activeGeneration").get("candidates").forEach(candidate -> followRows.add(
                        new ActiveCreatorRecommendationCandidate(
                                seed, candidate.get("creatorId").asLong(), null, null, candidate.get("rank").asInt())));
            }
        }

        interestRows.forEach(row -> excludeWithoutSpace(excluded, withSpace, row.creatorId()));
        followRows.forEach(row -> excludeWithoutSpace(excluded, withSpace, row.candidateCreatorId()));

        return toNode(policy.recommend(followRows, interestRows, excluded));
    }

    private void excludeWithoutSpace(Set<Long> excluded, Set<Long> withSpace, Long creatorId) {
        if (!withSpace.contains(creatorId)) {
            excluded.add(creatorId);
        }
    }

    private ObjectNode toNode(Recommendation recommendation) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("policyVersion", recommendation.policyVersion());
        ObjectNode counts = node.putObject("validSourceCounts");
        counts.put("interest", recommendation.validInterestSources());
        counts.put("follow", recommendation.validFollowSources());
        ArrayNode items = node.putArray("items");
        recommendation.items().forEach(item -> {
            ObjectNode itemNode = items.addObject();
            itemNode.put("creatorId", item.creatorId());
            itemNode.put("aggregateScore", item.aggregateScore().setScale(8).toPlainString());
            ArrayNode interestCodes = itemNode.putArray("interestCodes");
            item.interestCodes().forEach(interestCodes::add);
            ArrayNode seeds = itemNode.putArray("seedCreatorIds");
            item.seedCreatorIds().forEach(seeds::add);
        });
        return node;
    }
}
