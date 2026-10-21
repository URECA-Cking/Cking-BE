package kr.co.cking.auth.presentation;

import static kr.co.cking.auth.presentation.RedisOAuth2AuthorizationRequestRepository.BINDING_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** 실제 콜백으로 로그인을 끝낸 뒤 제공자 토큰이 서버 메모리에 남지 않는지 확인한다(#713). */
@SpringBootTest(properties = {
        "spring.profiles.active=local,oauth",
        "OAUTH_GOOGLE_CLIENT_ID=test-google-client-id",
        "OAUTH_GOOGLE_CLIENT_SECRET=test-google-client-secret",
        "OAUTH_KAKAO_CLIENT_ID=test-kakao-rest-api-key",
        "OAUTH_KAKAO_CLIENT_SECRET=test-kakao-client-secret"
})
@AutoConfigureMockMvc
class OAuth2LoginAuthorizedClientIntegrationTest {

    private static final AtomicInteger TOKEN_REQUESTS = new AtomicInteger();
    private static final long KAKAO_USER_ID = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
    private static HttpServer kakao;

    @Autowired private MockMvc mockMvc;
    @Autowired private OAuth2AuthorizedClientService authorizedClientService;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 토큰 발급과 사용자 정보 조회만 이 가짜 Kakao로 보낸다.
    @DynamicPropertySource
    static void kakaoProvider(DynamicPropertyRegistry registry) throws IOException {
        kakao = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        kakao.createContext("/oauth/token", exchange -> {
            TOKEN_REQUESTS.incrementAndGet();
            respond(exchange, "{\"access_token\":\"provider-access-token\",\"token_type\":\"bearer\",\"expires_in\":3600}");
        });
        kakao.createContext("/v2/user/me", exchange -> respond(exchange, "{\"id\":" + KAKAO_USER_ID + "}"));
        kakao.start();
        String baseUrl = "http://127.0.0.1:" + kakao.getAddress().getPort();
        registry.add("spring.security.oauth2.client.provider.kakao.token-uri", () -> baseUrl + "/oauth/token");
        registry.add("spring.security.oauth2.client.provider.kakao.user-info-uri", () -> baseUrl + "/v2/user/me");
    }

    @AfterAll
    static void stopKakao() {
        kakao.stop(0);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.queryForList(
                        "SELECT member_id FROM oauth_account WHERE provider = 'KAKAO' AND provider_user_id = ?",
                        Long.class, String.valueOf(KAKAO_USER_ID))
                .forEach(memberId -> {
                    jdbcTemplate.update("DELETE FROM oauth_account WHERE member_id = ?", memberId);
                    jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
                });
    }

    @Test
    void 로그인이_끝나도_제공자_토큰을_서버_메모리에_저장하지_않는다() throws Exception {
        MvcResult start = mockMvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        MvcResult callback = mockMvc.perform(get("/login/oauth2/code/kakao")
                        .cookie(new Cookie(BINDING_COOKIE, bindingOf(start.getResponse())))
                        .param(OAuth2ParameterNames.CODE, "test-code")
                        .param(OAuth2ParameterNames.STATE, stateOf(start)))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(callback.getResponse().getRedirectedUrl()).startsWith("http://localhost:5173/oauth/callback?code=");
        assertThat(TOKEN_REQUESTS).hasValue(1);
        assertThat((Object) authorizedClientService.loadAuthorizedClient("kakao", String.valueOf(KAKAO_USER_ID))).isNull();
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String stateOf(MvcResult result) {
        String encoded = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl())
                .build()
                .getQueryParams()
                .getFirst(OAuth2ParameterNames.STATE);
        return UriUtils.decode(encoded, StandardCharsets.UTF_8);
    }

    private String bindingOf(MockHttpServletResponse response) {
        String setCookie = response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(cookie -> cookie.startsWith(BINDING_COOKIE + "="))
                .findFirst()
                .orElseThrow();
        return setCookie.substring(BINDING_COOKIE.length() + 1, setCookie.indexOf(';'));
    }
}
