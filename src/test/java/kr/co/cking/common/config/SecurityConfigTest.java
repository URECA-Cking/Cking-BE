package kr.co.cking.common.config;

import kr.co.cking.common.security.RestAccessDeniedHandler;
import kr.co.cking.common.security.RestAuthenticationEntryPoint;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.presentation.MemberController;
import kr.co.cking.member.presentation.UserSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spring Security 기반 설정이 기존 API와 문서 인증 경로에 미치는 영향을 검증한다. */
@WebMvcTest(controllers = MemberController.class)
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class
})
@TestPropertySource(properties = {
        "cking.cors.allowed-origins=https://frontend.cking.co.kr",
        "cking.cors.allow-credentials=false"
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberQueryService memberQueryService;

    /** 인증 전환 전 API가 인증 없이도 기존처럼 호출되는지 검증한다. */
    @Test
    void 인증_전환_전_API는_인증_없이_호출할_수_있다() throws Exception {
        when(memberQueryService.findUsers()).thenReturn(List.of(new UserSummary(1L, "홍길동")));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));
    }

    /** 인증된 요청도 기존 API의 공개 동작을 바꾸지 않는지 검증한다. */
    @Test
    @WithMockUser(username = "member-1")
    void 인증된_호출자도_기존_API를_호출할_수_있다() throws Exception {
        when(memberQueryService.findUsers()).thenReturn(List.of(new UserSummary(1L, "홍길동")));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));
    }

    /** Authorization 헤더를 포함한 허용 origin의 사전 요청을 처리하는지 검증한다. */
    @Test
    void 허용된_origin의_CORS_사전_요청을_처리한다() throws Exception {
        mockMvc.perform(options("/api/users")
                        .header(ORIGIN, "https://frontend.cking.co.kr")
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, "https://frontend.cking.co.kr"))
                .andExpect(result -> assertThat(result.getResponse().getHeader(ACCESS_CONTROL_ALLOW_HEADERS))
                        .containsIgnoringCase("Authorization"))
                .andExpect(result -> assertThat(result.getResponse().getHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS))
                        .isNull());
    }

    /** Swagger 경로는 Security 체인의 인증 대상으로 잡히지 않는지 검증한다. */
    @Test
    void Swagger_경로는_Security_인증으로_거부되지_않는다() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

}
