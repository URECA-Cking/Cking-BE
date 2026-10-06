package kr.co.cking.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 추천 적재 엔드포인트에서만 {@value #HEADER} 헤더로 배치를 인증한다.
 * 헤더가 있으면 JWT로 되돌아가지 않고 키만 판단한다(비었거나 틀리면 401). 헤더가 없으면 아무 일도 하지 않아
 * 기존 ADMIN JWT 흐름이 그대로 동작한다. 키는 SHA-256 해시로만 설정에 두고, 해시 바이트를 상수 시간으로 비교한다.
 * 적재 엔드포인트 목록과 "키로 인증하는 요청" 판단은 {@link #carriesApiKey}가 한 곳에서 제공한다.
 */
public class RecommendationApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Cking-Recommendation-Key";

    /** API Key로 적재할 수 있는 엔드포인트. 인가 규칙과 이 필터가 같은 객체를 쓴다. 새 적재 API는 여기에만 추가한다. */
    public static final RequestMatcher WRITE_ENDPOINTS = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern(HttpMethod.PUT, "/api/admin/creators/*/similar"));

    private static final Logger log = LoggerFactory.getLogger(RecommendationApiKeyAuthenticationFilter.class);
    private static final int SHA_256_BYTES = 32;

    private final List<byte[]> allowedDigests;
    private final AuthenticationEntryPoint entryPoint;

    public RecommendationApiKeyAuthenticationFilter(List<String> allowedKeyHashes, AuthenticationEntryPoint entryPoint) {
        this.allowedDigests = allowedKeyHashes.stream()
                .map(String::trim)
                .filter(hash -> !hash.isEmpty())
                .map(RecommendationApiKeyAuthenticationFilter::parseDigest)
                .toList();
        this.entryPoint = entryPoint;
        if (allowedDigests.isEmpty()) {
            log.warn("cking.recommendation.api-key-hashes가 비어 있어 추천 적재 API Key 인증이 모두 거부됩니다. "
                    + "배치가 키로 적재하려면 키의 SHA-256 해시를 설정하세요.");
        }
    }

    /** 적재 엔드포인트 요청이고 키 헤더가 있으면 이 요청은 키로만 인증한다(JWT는 보지 않는다). */
    public static boolean carriesApiKey(HttpServletRequest request) {
        return WRITE_ENDPOINTS.matches(request) && request.getHeader(HEADER) != null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!carriesApiKey(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Enumeration<String> values = request.getHeaders(HEADER);
        String key = values.nextElement();
        if (values.hasMoreElements() || !isAllowed(key)) {
            // 키 값은 로그에 남기지 않는다. 대입 시도를 알아볼 수 있도록 요청 위치만 기록한다.
            log.warn("추천 적재 API Key 인증 실패: {} {} (remote={})",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("추천 적재 API Key가 유효하지 않습니다."));
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new RecommendationApiKeyAuthentication());
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }

    /** 등록된 모든 해시와 끝까지 비교해 어느 키가 맞는지에 따라 걸리는 시간이 달라지지 않게 한다. */
    private boolean isAllowed(String key) {
        if (key.isBlank()) {
            return false;
        }
        byte[] digest = sha256(key);
        boolean matched = false;
        for (byte[] allowed : allowedDigests) {
            matched |= MessageDigest.isEqual(allowed, digest);
        }
        return matched;
    }

    private static byte[] parseDigest(String hex) {
        byte[] digest;
        try {
            digest = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("cking.recommendation.api-key-hashes는 SHA-256 hex여야 합니다.", exception);
        }
        if (digest.length != SHA_256_BYTES) {
            throw new IllegalStateException("cking.recommendation.api-key-hashes는 SHA-256(64자 hex)이어야 합니다.");
        }
        return digest;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
