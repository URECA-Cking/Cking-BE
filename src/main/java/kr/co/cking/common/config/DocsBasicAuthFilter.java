package kr.co.cking.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * API 문서 경로에 HTTP Basic 인증을 건다.
 * 개발 서버는 인터넷에 공개되어 있어 인증 없이 전체 API 목록이 노출된다.
 * 문서 경로만 검사하며 그 외 요청은 그대로 통과시킨다.
 */
public class DocsBasicAuthFilter extends OncePerRequestFilter {

    private static final String[] PROTECTED_PREFIXES = {"/swagger-ui", "/v3/api-docs"};
    private static final String BASIC_PREFIX = "Basic ";

    private final String username;
    private final String password;

    public DocsBasicAuthFilter(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isProtected(request.getRequestURI()) || isAuthorized(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            chain.doFilter(request, response);
            return;
        }

        // 이 헤더가 있어야 브라우저가 아이디·비밀번호 입력 창을 띄운다.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Cking API Docs\", charset=\"UTF-8\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private boolean isProtected(String uri) {
        for (String prefix : PROTECTED_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAuthorized(String authorization) {
        if (authorization == null || !authorization.startsWith(BASIC_PREFIX)) {
            return false;
        }

        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(BASIC_PREFIX.length())),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }

        // 비밀번호에도 콜론이 들어갈 수 있으므로 첫 콜론만 구분자로 쓴다.
        int separator = decoded.indexOf(':');
        if (separator < 0) {
            return false;
        }

        // 일치하는 앞부분 길이로 값을 추측하지 못하도록 상수 시간 비교를 사용한다.
        return matches(username, decoded.substring(0, separator))
                && matches(password, decoded.substring(separator + 1));
    }

    private boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
