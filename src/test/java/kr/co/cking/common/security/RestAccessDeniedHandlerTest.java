package kr.co.cking.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class RestAccessDeniedHandlerTest {

    private final RestAccessDeniedHandler accessDeniedHandler = new RestAccessDeniedHandler();

    /** 인가되지 않은 요청이 공통 FORBIDDEN 응답으로 변환되는지 검증한다. */
    @Test
    void 인가되지_않은_요청은_FORBIDDEN_응답을_반환한다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        accessDeniedHandler.handle(
                new MockHttpServletRequest("GET", "/api/admin/events"),
                response,
                new AccessDeniedException("권한 없음")
        );

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"FORBIDDEN\"")
                .contains("\"message\":\"권한이 없습니다.\"");
    }
}
