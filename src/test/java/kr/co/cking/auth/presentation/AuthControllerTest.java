package kr.co.cking.auth.presentation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.auth.application.AccessTokenService;
import kr.co.cking.auth.application.LoginCodeService;
import kr.co.cking.auth.application.dto.AccessTokenResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Login Code 교환 API의 공통 응답과 입력 검증을 확인한다. */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginCodeService loginCodeService;

    @MockitoBean
    private AccessTokenService accessTokenService;

    /** 유효한 Login Code를 Access Token 공통 응답으로 바꾸는지 검증한다. */
    @Test
    void LoginCode를_AccessToken으로_교환한다() throws Exception {
        when(loginCodeService.consume("one-time-code")).thenReturn(17L);
        when(accessTokenService.issue(17L)).thenReturn(new AccessTokenResult("access-token", "Bearer", 1800));

        mockMvc.perform(post("/api/auth/token")
                        .contentType("application/json")
                        .content("{\"code\":\"one-time-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800));

        verify(loginCodeService).consume("one-time-code");
        verify(accessTokenService).issue(17L);
    }

    /** 비어 있는 Login Code 요청은 Access Token 발급 전에 입력 오류로 거절하는지 검증한다. */
    @Test
    void LoginCode가_비어_있으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/auth/token")
                        .contentType("application/json")
                        .content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
