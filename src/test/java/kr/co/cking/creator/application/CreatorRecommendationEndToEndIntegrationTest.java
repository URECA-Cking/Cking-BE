package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kr.co.cking.auth.application.port.AccessTokenIssuer;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 관심 분야 저장 → 분야별 후보 적재(API Key) → 개인화 조회 → 노출·클릭 → 팔로우 전환까지
 * 실제 HTTP·서명 JWT·MySQL을 통과하는 AI 크리에이터 추천 전체 흐름 검증(#609).
 * 적재는 실제 활성 분류체계의 한 분야를 쓰므로 종료 시 원래 활성 세대로 되돌린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "cking.recommendation.tracking.cleanup-enabled=false",
        "cking.recommendation.tracking.recovery-enabled=false",
        // SHA-256("recommendation-e2e-key-0123456789abcdef")
        "cking.recommendation.api-key-hashes=4a0e928222baf8d0feb20cc9f3332d8f25abe3e7dbaa78f23c97714bb236e74c"})
class CreatorRecommendationEndToEndIntegrationTest {
    private static final String API_KEY = "recommendation-e2e-key-0123456789abcdef";
    private static final String MODEL = "e2e-test-model";

    @LocalServerPort int port;
    @Autowired AccessTokenIssuer tokens;
    @Autowired MemberRepository members;
    @Autowired CreatorRepository creators;
    @Autowired CreatorSpaceRepository spaces;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("recommendationTrackingExecutor") ThreadPoolTaskExecutor executor;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Member> fixtureMembers = new ArrayList<>();
    private final List<Creator> fixtureCreators = new ArrayList<>();
    private final List<CreatorSpace> fixtureSpaces = new ArrayList<>();

    private String taxonomyVersion;
    private String interestCode;
    private Long originalGeneration;
    private long maxGenerationBefore;

    @BeforeEach
    void rememberActiveGeneration() throws Exception {
        JsonNode catalog = json.readTree(call("GET", "/api/interests", null, null, null).body()).get("data");
        taxonomyVersion = catalog.get("taxonomyVersion").asText();
        interestCode = catalog.get("items").get(0).get("interestCode").asText();
        List<Long> current = jdbc.queryForList(
                "SELECT current_generation_id FROM interest_recommendation_state WHERE taxonomy_version = ? AND interest_code = ?",
                Long.class, taxonomyVersion, interestCode);
        originalGeneration = current.isEmpty() ? null : current.get(0);
        maxGenerationBefore = jdbc.queryForObject(
                "SELECT COALESCE(MAX(generation_id), 0) FROM interest_recommendation_generation", Long.class);
    }

    @AfterEach
    void cleanUp() throws Exception {
        executor.submit(() -> { }).get(15, TimeUnit.SECONDS);
        if (originalGeneration == null) {
            jdbc.update("DELETE FROM interest_recommendation_state WHERE taxonomy_version = ? AND interest_code = ?",
                    taxonomyVersion, interestCode);
        } else {
            jdbc.update("UPDATE interest_recommendation_state SET current_generation_id = ? "
                    + "WHERE taxonomy_version = ? AND interest_code = ?", originalGeneration, taxonomyVersion, interestCode);
        }
        jdbc.update("DELETE FROM interest_recommendation_candidate WHERE generation_id IN "
                + "(SELECT generation_id FROM interest_recommendation_generation WHERE generation_id > ? AND model_version = ?)",
                maxGenerationBefore, MODEL);
        jdbc.update("DELETE FROM interest_recommendation_generation WHERE generation_id > ? AND model_version = ?",
                maxGenerationBefore, MODEL);
        for (Member member : fixtureMembers) {
            jdbc.update("DELETE FROM creator_follow WHERE member_id = ?", member.getMemberId());
            jdbc.update("DELETE FROM creator_recommendation_request WHERE member_id = ?", member.getMemberId());
            jdbc.update("DELETE FROM member_interest WHERE member_id = ?", member.getMemberId());
        }
        spaces.deleteAll(fixtureSpaces);
        creators.deleteAll(fixtureCreators);
        members.deleteAll(fixtureMembers);
    }

    @Test
    void 관심_분야_저장부터_후보_적재_개인화_조회_행동_기록_팔로우_전환까지_이어진다() throws Exception {
        Member fan = member();
        Member stranger = member();
        String fanToken = token(fan);
        Creator own = creatorWithSpace(fan.getMemberId());
        Creator first = creatorWithSpace(member().getMemberId());
        Creator second = creatorWithSpace(member().getMemberId());
        Creator third = creatorWithSpace(member().getMemberId());
        Creator withoutSpace = creator(member().getMemberId());

        // 1. 관심 분야 저장: 검증 실패는 기존 선택을 바꾸지 않고, 같은 요청 반복은 선택 시각을 바꾸지 않는다.
        assertThat(call("GET", "/api/me/interests", null, null, null).statusCode()).isEqualTo(401);
        assertThat(putInterests(fanToken, List.of(interestCode, interestCode)).statusCode()).isEqualTo(400);
        assertThat(putInterests(fanToken, List.of(interestCode)).statusCode()).isEqualTo(200);
        Object selectedAt = jdbc.queryForObject("SELECT selected_at FROM member_interest WHERE member_id = ?",
                Object.class, fan.getMemberId());
        assertThat(putInterests(fanToken, List.of(interestCode)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT selected_at FROM member_interest WHERE member_id = ?",
                Object.class, fan.getMemberId())).isEqualTo(selectedAt);

        // 2. 적재 전에는 우리 후보가 관심 분야 추천으로 나오지 않는다. 기존 세대가 없는 DB에서는 인기순 대체에 섞일 수 있다.
        JsonNode beforeIngest = recommend(fanToken, 20);
        if ("INTEREST_PERSONALIZED_V1".equals(beforeIngest.get("policyVersion").asText())) {
            assertThat(creatorIds(beforeIngest)).doesNotContain(first.getCreatorId(), second.getCreatorId(), third.getCreatorId());
        }

        // 3. 후보 적재: 인증·권한, 신규 세대, 같은 payload 재전송, 낮은 실행 번호.
        // 빈 DB에서도 "낮은 실행 번호"(sequence - 1)가 1 이상이도록 +2로 시작한다.
        long sequence = jdbc.queryForObject("SELECT COALESCE(MAX(application_sequence), 0) + 2 "
                + "FROM interest_recommendation_generation", Long.class);
        List<Creator> ranked = List.of(own, first, withoutSpace, second, third);
        Map<String, Object> batch = batch(sequence, ranked);
        assertThat(ingest(batch, null, null).statusCode()).isEqualTo(401);
        assertThat(ingest(batch, "wrong-key", null).statusCode()).isEqualTo(401);
        assertThat(ingest(batch, null, token(stranger)).statusCode()).isEqualTo(403);
        HttpResponse<String> applied = ingest(batch, API_KEY, null);
        assertThat(applied.statusCode()).isEqualTo(200);
        assertThat(json.readTree(applied.body()).get("data").get("applied").asBoolean()).isTrue();
        assertThat(json.readTree(ingest(batch, API_KEY, null).body()).get("data").get("applied").asBoolean()).isFalse();
        assertThat(ingest(batch(sequence - 1, ranked), API_KEY, null).statusCode()).isEqualTo(409);
        assertThat(ingest(batch(sequence + 1, List.of(first, first)), API_KEY, null).statusCode()).isEqualTo(400);

        // 4. 개인화 조회: 본인과 Space 없는 크리에이터는 제외되고 적재 순위가 유지된다.
        JsonNode personalized = recommend(fanToken, 20);
        assertThat(personalized.get("policyVersion").asText()).isEqualTo("INTEREST_PERSONALIZED_V1");
        assertThat(creatorIds(personalized)).containsExactly(first.getCreatorId(), second.getCreatorId(), third.getCreatorId());
        assertThat(creatorIds(recommend(fanToken, 2))).containsExactly(first.getCreatorId(), second.getCreatorId());

        // 5. 팔로우한 크리에이터는 다음 조회부터 빠진다.
        assertThat(call("PUT", "/api/creators/" + first.getCreatorId() + "/follow", fanToken, null, null).statusCode()).isEqualTo(200);
        JsonNode afterFollow = recommend(fanToken, 20);
        assertThat(creatorIds(afterFollow)).containsExactly(second.getCreatorId(), third.getCreatorId());

        // 6. 노출·클릭 기록: 소유권과 반환 카드 검증, 같은 이벤트 재전송은 멱등, 내용이 다르면 충돌.
        String requestId = afterFollow.get("recommendationRequestId").asText();
        long target = second.getCreatorId();
        String impression = event(UUID.randomUUID().toString(), requestId, target, "IMPRESSION");
        String click = event(UUID.randomUUID().toString(), requestId, target, "CLICK");
        String body = "{\"events\":[" + impression + "," + click + "]}";
        assertThat(call("POST", "/api/me/creator-recommendation-events", null, body, null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/me/creator-recommendation-events", token(stranger), body, null).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/me/creator-recommendation-events", fanToken,
                "{\"events\":[" + event(UUID.randomUUID().toString(), requestId, first.getCreatorId(), "CLICK") + "]}", null)
                .statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/me/creator-recommendation-events", fanToken, body, null).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/me/creator-recommendation-events", fanToken, body, null).statusCode()).isEqualTo(200);
        String reusedId = json.readTree(impression).get("eventId").asText();
        assertThat(call("POST", "/api/me/creator-recommendation-events", fanToken,
                "{\"events\":[" + event(reusedId, requestId, target, "CLICK") + "]}", null).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_interaction WHERE member_id = ?",
                Long.class, fan.getMemberId())).isEqualTo(2);

        // 7. 클릭한 크리에이터를 팔로우하면 추천 전환으로 귀속된다.
        assertThat(call("PUT", "/api/creators/" + target + "/follow", fanToken, null, null).statusCode()).isEqualTo(200);
        executor.submit(() -> { }).get(15, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT request_id FROM creator_recommendation_conversion WHERE member_id = ?",
                String.class, fan.getMemberId())).isEqualTo(requestId);
    }

    private JsonNode recommend(String token, int size) throws Exception {
        HttpResponse<String> response = call("GET", "/api/me/creator-recommendations?size=" + size, token, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("data");
    }

    private List<Long> creatorIds(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.get("items").forEach(item -> ids.add(item.get("creatorId").asLong()));
        return ids;
    }

    private HttpResponse<String> putInterests(String token, List<String> codes) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("taxonomyVersion", taxonomyVersion);
        body.put("interestCodes", codes);
        return call("PUT", "/api/me/interests", token, json.writeValueAsString(body), null);
    }

    private Map<String, Object> batch(long sequence, List<Creator> ranked) {
        String inputHash = String.format("%064x", sequence);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Map<String, Object> candidate = new HashMap<>();
            candidate.put("interestCode", interestCode);
            candidate.put("creatorId", ranked.get(i).getCreatorId());
            candidate.put("score", 0.9 - i * 0.1);
            candidate.put("rank", i + 1);
            candidate.put("method", "INTEREST_M3_V1");
            candidate.put("modelVersion", MODEL);
            candidate.put("inputHash", inputHash);
            candidates.add(candidate);
        }
        Map<String, Object> batch = new HashMap<>();
        batch.put("applicationSequence", sequence);
        batch.put("taxonomyVersion", taxonomyVersion);
        batch.put("taxonomyHash", jdbc.queryForObject("SELECT taxonomy_hash FROM interest_taxonomy WHERE taxonomy_version = ?",
                String.class, taxonomyVersion));
        batch.put("interestCode", interestCode);
        batch.put("method", "INTEREST_M3_V1");
        batch.put("modelVersion", MODEL);
        batch.put("inputHash", inputHash);
        batch.put("candidates", candidates);
        return batch;
    }

    private HttpResponse<String> ingest(Map<String, Object> batch, String key, String bearer) throws Exception {
        return call("PUT", "/api/admin/interests/" + interestCode + "/recommendations", bearer,
                json.writeValueAsString(batch), key);
    }

    private String event(String eventId, String requestId, long creatorId, String type) {
        return "{\"eventId\":\"" + eventId + "\",\"recommendationRequestId\":\"" + requestId
                + "\",\"creatorId\":" + creatorId + ",\"eventType\":\"" + type + "\"}";
    }

    private Member member() {
        Member member = members.saveAndFlush(new Member("e2e609-" + UUID.randomUUID(), null, null, MemberRole.USER));
        fixtureMembers.add(member);
        return member;
    }

    private String token(Member member) {
        return tokens.issue(member.getMemberId(), MemberRole.USER).accessToken();
    }

    private Creator creator(Long memberId) {
        Creator creator = creators.saveAndFlush(new Creator(memberId, "e2e609-" + UUID.randomUUID()));
        fixtureCreators.add(creator);
        return creator;
    }

    private Creator creatorWithSpace(Long memberId) {
        Creator creator = creator(memberId);
        var template = new CreatorSpaceTemplate(memberId, "intro", "profile", "banner", "creator-{creatorId}");
        fixtureSpaces.add(spaces.saveAndFlush(
                CreatorSpace.fromTemplate(creator.getCreatorId(), template, "e2e609-" + UUID.randomUUID())));
        return creator;
    }

    private HttpResponse<String> call(String method, String path, String bearer, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (key != null) builder.header("X-Cking-Recommendation-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
