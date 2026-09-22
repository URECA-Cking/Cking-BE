package kr.co.cking.common.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class DocsBasicAuthFilterTest {

    private static final String USERNAME = "docs-user";
    private static final String PASSWORD = "docs-password";

    private final DocsBasicAuthFilter filter = new DocsBasicAuthFilter(USERNAME, PASSWORD);

    /** 인증 정보가 없으면 브라우저가 입력 창을 띄우도록 401과 WWW-Authenticate를 반환한다. */
    @Test
    void 인증_정보가_없으면_문서_요청을_거부한다() throws Exception {
        MockHttpServletResponse response = doFilter("/swagger-ui/index.html", null);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic realm=");
    }

    @Test
    void 잘못된_인증_정보는_문서_요청을_거부한다() throws Exception {
        MockHttpServletResponse response = doFilter("/v3/api-docs", basic(USERNAME, "wrong"));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void 올바른_인증_정보는_문서_요청을_통과시킨다() throws Exception {
        MockHttpServletResponse response = doFilter("/v3/api-docs", basic(USERNAME, PASSWORD));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    /** 문서 외 경로는 인증 대상이 아니다. API 요청이 이 필터의 영향을 받지 않아야 한다. */
    @Test
    void 문서가_아닌_경로는_인증_없이_통과시킨다() throws Exception {
        MockHttpServletResponse response = doFilter("/api/events", null);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    private MockHttpServletResponse doFilter(String uri, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (authorization != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private String basic(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
