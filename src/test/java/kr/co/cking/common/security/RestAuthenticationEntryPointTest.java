package kr.co.cking.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

class RestAuthenticationEntryPointTest {

    private final RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint();

    /** 인증되지 않은 요청이 공통 UNAUTHORIZED 응답으로 변환되는지 검증한다. */
    @Test
    void 인증되지_않은_요청은_UNAUTHORIZED_응답을_반환한다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(
                new MockHttpServletRequest("GET", "/api/me"),
                response,
                new InsufficientAuthenticationException("인증 정보 없음")
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"message\":\"인증이 필요합니다.\"");
    }
}
