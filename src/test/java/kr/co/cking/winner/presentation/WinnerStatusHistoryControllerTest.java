package kr.co.cking.winner.presentation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import kr.co.cking.common.config.WebMvcConfig;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.CurrentMemberIdArgumentResolver;
import kr.co.cking.winner.application.WinnerStatusHistoryQueryService;
import kr.co.cking.winner.application.WinnerStatusHistoryResult;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Winner 상태 이력 조회 HTTP 경계의 입력 검증과 오류 응답을 검증한다. */
@WebMvcTest(WinnerStatusHistoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebMvcConfig.class, CurrentMemberIdArgumentResolver.class})
class WinnerStatusHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WinnerStatusHistoryQueryService winnerStatusHistoryQueryService;

    @org.junit.jupiter.api.BeforeEach
    void authenticatedMember() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "none").subject("2").claim("role", "USER").build(), List.of()));
    }

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 상태 이력의 변경 전후 상태와 감사 정보를 공통 성공 응답으로 반환한다. */
    @Test
    void Winner_상태_이력을_공통_성공_응답으로_반환한다() throws Exception {
        when(winnerStatusHistoryQueryService.getHistory(100L, 2L)).thenReturn(List.of(
                new WinnerStatusHistoryResult(
                        900L,
                        WinnerManagementStatus.SELECTED,
                        WinnerManagementStatus.DISQUALIFIED,
                        "참여 조건 미충족",
                        1L,
                        Instant.parse("2026-09-21T01:00:00Z")
                )
        ));

        mockMvc.perform(get("/api/winners/100/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].historyId").value(900))
                .andExpect(jsonPath("$.data[0].beforeStatus").value("SELECTED"))
                .andExpect(jsonPath("$.data[0].afterStatus").value("DISQUALIFIED"))
                .andExpect(jsonPath("$.data[0].reason").value("참여 조건 미충족"))
                .andExpect(jsonPath("$.data[0].changedBy").value(1))
                .andExpect(jsonPath("$.data[0].changedAt").value("2026-09-21T01:00:00Z"));
    }

    /** winnerId가 양수가 아니면 Controller 경계에서 입력 검증 오류를 반환한다. */
    @Test
    void 상태_이력_조회_winnerId가_0이면_입력_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(get("/api/winners/0/history"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** USER가 다른 사용자의 Winner를 조회하면 권한 없음 응답을 반환한다. */
    @Test
    void 다른_사용자의_Winner_상태_이력은_FORBIDDEN_응답을_반환한다() throws Exception {
        when(winnerStatusHistoryQueryService.getHistory(100L, 2L))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get("/api/winners/100/history"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
