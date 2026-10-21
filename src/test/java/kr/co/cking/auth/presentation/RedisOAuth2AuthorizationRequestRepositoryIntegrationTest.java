package kr.co.cking.auth.presentation;

import static kr.co.cking.auth.presentation.RedisOAuth2AuthorizationRequestRepository.BINDING_COOKIE;
import static kr.co.cking.auth.presentation.RedisOAuth2AuthorizationRequestRepository.KEY_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@SpringBootTest(properties = {
        "spring.profiles.active=local,oauth",
        "OAUTH_GOOGLE_CLIENT_ID=test-google-client-id",
        "OAUTH_GOOGLE_CLIENT_SECRET=test-google-client-secret",
        "OAUTH_KAKAO_CLIENT_ID=test-kakao-rest-api-key",
        "OAUTH_KAKAO_CLIENT_SECRET=test-kakao-client-secret",
        // 콜백 테스트가 외부 Kakao를 부르지 않도록 토큰 발급 주소를 닿지 않는 로컬 주소로 바꾼다.
        "spring.security.oauth2.client.provider.kakao.token-uri=http://127.0.0.1:9/oauth/token"
})
@AutoConfigureMockMvc
class RedisOAuth2AuthorizationRequestRepositoryIntegrationTest {

    private static final String PROVIDER_ERROR_REDIRECT = "http://localhost:5173/oauth/callback?error=provider_error";

    @Autowired private MockMvc mockMvc;
    @Autowired private RedisOAuth2AuthorizationRequestRepository repository;
    @Autowired private StringRedisTemplate redisTemplate;

    private final List<String> states = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        states.forEach(state -> {
            Set<String> keys = keysOf(state);
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        });
    }

    @Test
    void 로그인_시작은_세션을_만들지_않고_브라우저_쿠키와_state로_Redis에_저장한다() throws Exception {
        MvcResult result = startLogin("google");
        String state = stateOf(result);
        String bindingCookie = bindingSetCookie(result.getResponse());

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
        assertThat(bindingCookie).contains("HttpOnly", "SameSite=Lax", "Path=/login/oauth2/code", "Max-Age=600");
        assertThat(keysOf(state)).singleElement()
                .satisfies(key -> assertThat(key).doesNotContain(bindingOf(result.getResponse())))
                .satisfies(key -> assertThat(redisTemplate.getExpire(key)).isBetween(590L, 600L));
    }

    @Test
    void 콜백에서_꺼낸_인가_요청은_저장한_내용과_같고_다시_꺼낼_수_없다() throws Exception {
        MvcResult result = startLogin("kakao");
        String state = stateOf(result);
        String binding = bindingOf(result.getResponse());

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        OAuth2AuthorizationRequest first = repository.removeAuthorizationRequest(callback(state, binding), firstResponse);
        OAuth2AuthorizationRequest second =
                repository.removeAuthorizationRequest(callback(state, binding), new MockHttpServletResponse());

        assertThat(first).isNotNull();
        assertThat(first.getState()).isEqualTo(state);
        assertThat(first.getClientId()).isEqualTo("test-kakao-rest-api-key");
        assertThat(first.getAuthorizationUri()).isEqualTo("https://kauth.kakao.com/oauth/authorize");
        assertThat(first.getAuthorizationRequestUri()).isEqualTo(result.getResponse().getRedirectedUrl());
        assertThat((String) first.getAttribute(OAuth2ParameterNames.REGISTRATION_ID)).isEqualTo("kakao");
        assertThat(bindingSetCookie(firstResponse)).contains("Max-Age=0");
        assertThat(second).isNull();
        assertThat(keysOf(state)).isEmpty();
    }

    @Test
    void 같은_브라우저의_콜백이_동시에_와도_한_번만_꺼낸다() throws Exception {
        MvcResult result = startLogin("google");
        String state = stateOf(result);
        String binding = bindingOf(result.getResponse());
        Callable<OAuth2AuthorizationRequest> remove =
                () -> repository.removeAuthorizationRequest(callback(state, binding), new MockHttpServletResponse());

        List<OAuth2AuthorizationRequest> removed = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<OAuth2AuthorizationRequest>> futures = executor.invokeAll(List.of(
                    remove, remove, remove, remove, remove, remove, remove, remove));
            for (Future<OAuth2AuthorizationRequest> future : futures) {
                OAuth2AuthorizationRequest request = future.get(5, TimeUnit.SECONDS);
                if (request != null) {
                    removed.add(request);
                }
            }
        }

        assertThat(removed).hasSize(1);
    }

    @Test
    void 콜백은_Redis에_저장된_인가_요청을_꺼내_쓰고_지운다() throws Exception {
        MvcResult start = startLogin("kakao");
        String state = stateOf(start);

        MvcResult result = mockMvc.perform(get("/login/oauth2/code/kakao")
                        .cookie(new Cookie(BINDING_COOKIE, bindingOf(start.getResponse())))
                        .param(OAuth2ParameterNames.CODE, "test-code")
                        .param(OAuth2ParameterNames.STATE, state))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // 콜백이 Redis에서 인가 요청을 꺼냈다(키가 지워짐). 토큰 주소가 닿지 않아 로그인 자체는 실패한다.
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(PROVIDER_ERROR_REDIRECT);
        assertThat(keysOf(state)).isEmpty();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void 쿠키가_없는_다른_브라우저에서_연_콜백은_인가_요청을_찾지_못한다() throws Exception {
        String state = stateOf(startLogin("google"));

        MvcResult result = mockMvc.perform(get("/login/oauth2/code/google")
                        .param(OAuth2ParameterNames.CODE, "test-code")
                        .param(OAuth2ParameterNames.STATE, state))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(PROVIDER_ERROR_REDIRECT);
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(keysOf(state)).hasSize(1);
    }

    @Test
    void 다른_로그인의_쿠키로는_인가_요청을_찾지_못한다() throws Exception {
        String victimState = stateOf(startLogin("google"));
        String attackerBinding = bindingOf(startLogin("google").getResponse());

        assertThat(repository.removeAuthorizationRequest(callback(victimState, attackerBinding), new MockHttpServletResponse()))
                .isNull();
        assertThat(keysOf(victimState)).hasSize(1);
    }

    @Test
    void 저장한_인가_요청의_모든_필드가_그대로_되살아난다() {
        String state = "roundtrip-" + System.nanoTime();
        states.add(state);
        OAuth2AuthorizationRequest original = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-google-client-id")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .scopes(Set.of("profile", "email"))
                .state(state)
                .additionalParameters(Map.of(
                        PkceParameterNames.CODE_CHALLENGE, "challenge",
                        PkceParameterNames.CODE_CHALLENGE_METHOD, "S256",
                        OidcParameterNames.NONCE, "hashed-nonce"))
                .attributes(Map.of(
                        OAuth2ParameterNames.REGISTRATION_ID, "google",
                        PkceParameterNames.CODE_VERIFIER, "verifier",
                        OidcParameterNames.NONCE, "nonce"))
                .authorizationRequestUri("https://accounts.google.com/o/oauth2/v2/auth?state=" + state)
                .build();
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveAuthorizationRequest(original, new MockHttpServletRequest(), response);

        assertThat(repository.loadAuthorizationRequest(callback(state, bindingOf(response))))
                .usingRecursiveComparison()
                .isEqualTo(original);
    }

    @Test
    void JSON이_아닌_값은_없는_인가_요청으로_본다() throws Exception {
        MvcResult result = startLogin("google");
        String state = stateOf(result);
        redisTemplate.opsForValue().set(keysOf(state).iterator().next(), "not-json");

        assertThat(repository.loadAuthorizationRequest(callback(state, bindingOf(result.getResponse())))).isNull();
    }

    @Test
    void 필수_값이_빠진_값은_없는_인가_요청으로_본다() throws Exception {
        MvcResult result = startLogin("google");
        String state = stateOf(result);
        String key = keysOf(state).iterator().next();
        redisTemplate.opsForValue().set(key,
                redisTemplate.opsForValue().get(key).replaceAll("\"clientId\":\"[^\"]*\"", "\"clientId\":\"\""));

        assertThat(repository.loadAuthorizationRequest(callback(state, bindingOf(result.getResponse())))).isNull();
    }

    @Test
    void state나_쿠키가_없는_요청은_조회하지_않는다() {
        MockHttpServletRequest noState = new MockHttpServletRequest();
        noState.setCookies(new Cookie(BINDING_COOKIE, "binding"));
        MockHttpServletRequest noCookie = new MockHttpServletRequest();
        noCookie.setParameter(OAuth2ParameterNames.STATE, "state");

        assertThat(repository.loadAuthorizationRequest(noState)).isNull();
        assertThat(repository.removeAuthorizationRequest(noState, new MockHttpServletResponse())).isNull();
        assertThat(repository.loadAuthorizationRequest(noCookie)).isNull();
        assertThat(repository.removeAuthorizationRequest(noCookie, new MockHttpServletResponse())).isNull();
    }

    @Test
    void null_저장은_해당_인가_요청을_지운다() throws Exception {
        MvcResult result = startLogin("google");
        String state = stateOf(result);

        repository.saveAuthorizationRequest(
                null, callback(state, bindingOf(result.getResponse())), new MockHttpServletResponse());

        assertThat(keysOf(state)).isEmpty();
    }

    private MvcResult startLogin(String registrationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/" + registrationId))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        states.add(stateOf(result));
        return result;
    }

    // 주소의 state는 인코딩돼 있다(끝의 '='가 %3D). 콜백에서 서블릿이 풀어 주는 값과 같게 디코딩한다.
    private String stateOf(MvcResult result) {
        String encoded = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl())
                .build()
                .getQueryParams()
                .getFirst(OAuth2ParameterNames.STATE);
        return UriUtils.decode(encoded, StandardCharsets.UTF_8);
    }

    private String bindingSetCookie(HttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(cookie -> cookie.startsWith(BINDING_COOKIE + "="))
                .findFirst()
                .orElseThrow();
    }

    private String bindingOf(HttpServletResponse response) {
        String setCookie = bindingSetCookie(response);
        return setCookie.substring(BINDING_COOKIE.length() + 1, setCookie.indexOf(';'));
    }

    private Set<String> keysOf(String state) {
        return redisTemplate.keys(KEY_PREFIX + "*:" + state);
    }

    private MockHttpServletRequest callback(String state, String binding) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(OAuth2ParameterNames.STATE, state);
        request.setCookies(new Cookie(BINDING_COOKIE, binding));
        return request;
    }
}
