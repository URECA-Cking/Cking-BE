package kr.co.cking.auth.presentation;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.util.WebUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth 로그인 시작 때 만든 인가 요청을 Redis에 두어, 콜백을 다른 서버가 받아도 찾게 한다(#678).
 * 키는 state와 이 브라우저에 내린 쿠키 값으로 만든다. 쿠키가 없는 다른 브라우저로 콜백 주소를 열면 찾지 못해,
 * 남의 콜백 주소로 그 사람 계정에 로그인되는 일(로그인 CSRF)을 막는다. 세션 저장 방식이 막던 것과 같다.
 */
@Component
@Slf4j
public class RedisOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String KEY_PREFIX = "auth:oauth2-request:";
    static final String BINDING_COOKIE = "cking_oauth2_request";
    // 로그인 시작부터 제공자 화면을 거쳐 콜백까지 걸리는 시간의 상한이다. 측정값이 아니라, 보통 수 초~1·2분인 로그인에
    // 여유를 둔 값이다. 인증 없이 부를 수 있어 키가 공유 Redis(noeviction)에 쌓이므로 세션 기본값(30분)보다 짧게 둔다.
    static final Duration TTL = Duration.ofMinutes(10);
    private static final String CALLBACK_PATH = "/login/oauth2/code";
    private static final int BINDING_BYTE_LENGTH = 32;

    private final StringRedisTemplate redisTemplate;
    private final boolean secureCookie;
    private final SecureRandom secureRandom = new SecureRandom();
    // JDK 직렬화 대신 Spring Security가 허용한 타입만 되살리는 JSON을 쓴다.
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(RedisOAuth2AuthorizationRequestRepository.class.getClassLoader()))
            .build();

    public RedisOAuth2AuthorizationRequestRepository(
            StringRedisTemplate redisTemplate,
            @Value("${cking.auth.refresh-cookie-secure}") boolean secureCookie
    ) {
        this.redisTemplate = redisTemplate;
        this.secureCookie = secureCookie;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String key = key(request);
        if (key == null) {
            return null;
        }
        try {
            return read(redisTemplate.opsForValue().get(key));
        } catch (DataAccessException exception) {
            log.error("OAuth 인가 요청을 Redis에서 읽지 못했습니다.", exception);
            return null;
        }
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }
        Assert.hasText(authorizationRequest.getState(), "authorizationRequest.state cannot be empty");
        String binding = createBinding();
        redisTemplate.opsForValue().set(
                key(binding, authorizationRequest.getState()), jsonMapper.writeValueAsString(authorizationRequest), TTL);
        writeBindingCookie(response, binding, TTL);
    }

    /** 콜백에서 인가 요청을 꺼내며 지워(GETDEL), 같은 요청이 두 번 쓰이지 않게 한다. */
    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String key = key(request);
        if (key == null) {
            return null;
        }
        writeBindingCookie(response, "", Duration.ZERO);
        try {
            return read(redisTemplate.opsForValue().getAndDelete(key));
        } catch (DataAccessException exception) {
            // 콜백이 500으로 끝나지 않고 Spring Security의 로그인 실패 처리로 프런트에 돌아가게 한다.
            log.error("OAuth 인가 요청을 Redis에서 꺼내지 못했습니다.", exception);
            return null;
        }
    }

    private OAuth2AuthorizationRequest read(String json) {
        if (json == null) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, OAuth2AuthorizationRequest.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            // 읽지 못한 요청은 없는 요청으로 보고, Spring Security가 로그인 실패로 처리하게 한다.
            log.warn("OAuth 인가 요청을 읽지 못했습니다. failureType={}", exception.getClass().getName());
            return null;
        }
    }

    private String key(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        Cookie binding = WebUtils.getCookie(request, BINDING_COOKIE);
        if (state == null || binding == null || binding.getValue().isBlank()) {
            return null;
        }
        return key(binding.getValue(), state);
    }

    // 쿠키 원문은 Redis에 남기지 않는다. Redis 값을 본 사람이 쿠키를 만들어 콜백을 열 수 없게 한다.
    private String key(String binding, String state) {
        return KEY_PREFIX + sha256(binding) + ":" + state;
    }

    private String createBinding() {
        byte[] bytes = new byte[BINDING_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // 제공자에서 콜백으로 돌아오는 것은 다른 사이트에서 오는 GET 이동이라 SameSite=Lax여야 쿠키가 실린다.
    private void writeBindingCookie(HttpServletResponse response, String value, Duration maxAge) {
        if (response == null) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(BINDING_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path(CALLBACK_PATH)
                .maxAge(maxAge)
                .build()
                .toString());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
