package kr.co.cking.notification.presentation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.notification.application.NotificationReadResult;
import kr.co.cking.notification.application.NotificationReadService;
import kr.co.cking.notification.domain.NotificationErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** NotificationController의 읽음 처리 HTTP 계약을 검증한다. */
@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationReadService notificationReadService;

    /** 유효한 읽음 요청은 공통 성공 응답과 readAt을 반환한다. */
    @Test
    void 읽음_처리_성공_응답을_반환한다() throws Exception {
        when(notificationReadService.read(1L, 10L))
                .thenReturn(new NotificationReadResult(10L, Instant.parse("2026-09-17T00:00:00Z")));

        mockMvc.perform(patch("/api/me/notifications/10/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.notificationId").value(10))
                .andExpect(jsonPath("$.data.readAt").value("2026-09-17T00:00:00Z"));
    }

    /** 요청 본문에 userId가 없으면 공통 입력 검증 오류를 반환한다. */
    @Test
    void userId가_없으면_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(patch("/api/me/notifications/10/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 존재하지 않는 Notification은 도메인 오류 응답으로 변환한다. */
    @Test
    void 존재하지_않는_Notification은_404를_반환한다() throws Exception {
        when(notificationReadService.read(1L, 10L))
                .thenThrow(new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        mockMvc.perform(patch("/api/me/notifications/10/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    /** 타 사용자의 Notification은 권한 오류 응답으로 변환한다. */
    @Test
    void 타_사용자_Notification은_403을_반환한다() throws Exception {
        when(notificationReadService.read(1L, 10L))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(patch("/api/me/notifications/10/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
