package kr.co.cking.event.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.AdminClosingStatusQueryService;
import kr.co.cking.event.domain.EventStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminClosingStatusController.class)
class AdminClosingStatusControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AdminClosingStatusQueryService adminClosingStatusQueryService;

    /** 관리자 조회는 CLOSING 상태만 공통 응답 봉투에 담아 반환한다. */
    @Test
    void getClosingStatusReturnsOnlyClosingStatus() throws Exception {
        given(adminClosingStatusQueryService.getClosingStatus(1L, 10L)).willReturn(EventStatus.CLOSING);

        mockMvc.perform(get("/api/admin/events/{eventId}/closing-status", 10L)
                        .queryParam("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("CLOSING"))
                .andExpect(jsonPath("$.data.eventId").doesNotExist())
                .andExpect(jsonPath("$.data.progress").doesNotExist())
                .andExpect(jsonPath("$.data.pendingCount").doesNotExist())
                .andExpect(jsonPath("$.data.cutoffStreamId").doesNotExist());
    }

    /** 완료된 마감 조회는 CLOSED 상태를 반환한다. */
    @Test
    void getClosingStatusReturnsClosedStatus() throws Exception {
        given(adminClosingStatusQueryService.getClosingStatus(1L, 10L)).willReturn(EventStatus.CLOSED);

        mockMvc.perform(get("/api/admin/events/{eventId}/closing-status", 10L)
                        .queryParam("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
    }

    /** userId 쿼리 파라미터가 없으면 요청 검증 오류를 반환한다. */
    @Test
    void getClosingStatusRejectsMissingUserId() throws Exception {
        mockMvc.perform(get("/api/admin/events/{eventId}/closing-status", 10L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 0 이하 Event ID 또는 관리자 ID는 Controller 계층에서 거절한다. */
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void getClosingStatusRejectsNonPositiveIds(long invalidId) throws Exception {
        mockMvc.perform(get("/api/admin/events/{eventId}/closing-status", invalidId)
                        .queryParam("userId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/admin/events/{eventId}/closing-status", 10L)
                        .queryParam("userId", String.valueOf(invalidId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 관리자 권한 오류는 공통 FORBIDDEN 응답으로 변환한다. */
    @Test
    void getClosingStatusMapsForbiddenResponse() throws Exception {
        given(adminClosingStatusQueryService.getClosingStatus(1L, 10L))
                .willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get("/api/admin/events/{eventId}/closing-status", 10L)
                        .queryParam("userId", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
