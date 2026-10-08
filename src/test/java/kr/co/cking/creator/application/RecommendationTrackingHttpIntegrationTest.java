package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

/** 실제 HTTP/서명 JWT/DB를 통과하는 추천→행동→팔로우 계약 검증. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "cking.recommendation.tracking.cleanup-enabled=false", "cking.recommendation.tracking.recovery-enabled=false"})
class RecommendationTrackingHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired AccessTokenIssuer tokens;
    @Autowired MemberRepository members;
    @Autowired CreatorRepository creators;
    @Autowired CreatorSpaceRepository spaces;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("recommendationTrackingExecutor") ThreadPoolTaskExecutor executor;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Member> fixture = new ArrayList<>();
    private Creator creator;
    private CreatorSpace space;

    @AfterEach
    void cleanUp() throws Exception {
        executor.submit(() -> { }).get(15, TimeUnit.SECONDS);
        for (Member member : fixture) {
            jdbc.update("DELETE FROM creator_follow WHERE member_id = ?", member.getMemberId());
            jdbc.update("DELETE FROM creator_recommendation_request WHERE member_id = ?", member.getMemberId());
        }
        if (space != null) spaces.delete(space);
        if (creator != null) creators.delete(creator);
        members.deleteAll(fixture);
    }

    @Test
    void 서명_JWT로_추천_조회_노출_클릭_팔로우까지_실제_HTTP와_DB를_통과한다() throws Exception {
        Long fan = member();
        Long stranger = member();
        creator = creators.saveAndFlush(new Creator(member(), "http500-" + UUID.randomUUID()));
        var template = new CreatorSpaceTemplate(fan, "intro", "profile", "banner", "creator-{creatorId}");
        space = spaces.saveAndFlush(CreatorSpace.fromTemplate(creator.getCreatorId(), template, "http500-" + UUID.randomUUID()));
        String token = tokens.issue(fan, MemberRole.USER).accessToken();
        String other = tokens.issue(stranger, MemberRole.USER).accessToken();
        assertThat(call("GET", "/api/me/creator-recommendations", null, null).statusCode()).isEqualTo(401);
        var response = call("GET", "/api/me/creator-recommendations?size=20", token, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = json.readTree(response.body()).get("data");
        String requestId = data.get("recommendationRequestId").asText();
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(data.get("policyVersion").asText()).isEqualTo("POPULAR_FALLBACK_V1");
        long returnedCreator = data.get("items").get(0).get("creatorId").asLong();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_interaction WHERE member_id = ?", Long.class, fan)).isZero();

        String body = "{\"events\":[" + event(requestId, returnedCreator, "IMPRESSION") + "," + event(requestId, returnedCreator, "CLICK") + "]}";
        assertThat(call("POST", "/api/me/creator-recommendation-events", null, body).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/me/creator-recommendation-events", token + "broken", body).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/me/creator-recommendation-events", other, body).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/me/creator-recommendation-events", token, body).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/me/creator-recommendation-events", token, body).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_interaction WHERE member_id = ?", Long.class, fan)).isEqualTo(2);
        assertThat(call("PUT", "/api/creators/" + returnedCreator + "/follow", token, null).statusCode()).isEqualTo(200);
        executor.submit(() -> { }).get(15, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT request_id FROM creator_recommendation_conversion WHERE member_id = ?", String.class, fan)).isEqualTo(requestId);
    }

    private Long member() {
        Member member = members.saveAndFlush(new Member("http500-" + UUID.randomUUID(), null, null, MemberRole.USER));
        fixture.add(member);
        return member.getMemberId();
    }

    private String event(String requestId, long creatorId, String type) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"recommendationRequestId\":\"" + requestId
                + "\",\"creatorId\":" + creatorId + ",\"eventType\":\"" + type + "\"}";
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
