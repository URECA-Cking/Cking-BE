package kr.co.cking.notification.presentation;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.notification.application.NotificationQueryService;
import kr.co.cking.notification.application.NotificationReadResult;
import kr.co.cking.notification.application.NotificationReadService;
import kr.co.cking.notification.application.dto.NotificationSummary;
import kr.co.cking.notification.domain.NotificationErrorCode;
import kr.co.cking.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** NotificationController의 조회·읽음 처리 HTTP 계약을 검증한다. */
@WebMvcTest(NotificationController.class)
@kr.co.cking.common.security.WithMockJwt
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationQueryService notificationQueryService;

    @MockitoBean
    private NotificationReadService notificationReadService;

    @Test
    void 내_알림_목록은_페이지_응답과_알림_상세를_반환한다() throws Exception {
        NotificationSummary summary = summary(null);
        when(notificationQueryService.findMine(eq(1L), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/me/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].notificationId").value(1))
                .andExpect(jsonPath("$.data.items[0].event.eventId").value(10))
                .andExpect(jsonPath("$.data.items[0].drawing.drawType").value("INITIAL"))
                .andExpect(jsonPath("$.data.items[0].type").value("INITIAL_WINNER"))
                .andExpect(jsonPath("$.data.items[0].readAt").value(nullValue()))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void page가_음수면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/notifications").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 읽은_알림의_readAt은_UTC_시각으로_반환한다() throws Exception {
        when(notificationQueryService.findMine(eq(1L), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(summary(Instant.parse("2026-09-17T01:00:00Z"))), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/me/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].readAt").value("2026-09-17T01:00:00Z"));
    }

    @Test
    void size가_범위를_벗어나면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/notifications").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/me/notifications").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    void JWT가_없으면_목록_조회는_401과_UNAUTHORIZED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 읽음_처리_성공_응답을_반환한다() throws Exception {
        when(notificationReadService.read(1L, 10L))
                .thenReturn(new NotificationReadResult(10L, Instant.parse("2026-09-17T00:00:00Z")));

        mockMvc.perform(patch("/api/me/notifications/10/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.notificationId").value(10))
                .andExpect(jsonPath("$.data.readAt").value("2026-09-17T00:00:00Z"));
    }

    @Test
    void 읽음_처리_notificationId가_양수가_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(patch("/api/me/notifications/0/read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch("/api/me/notifications/-1/read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    void JWT가_없으면_읽음_처리는_401과_UNAUTHORIZED를_반환한다() throws Exception {
        mockMvc.perform(patch("/api/me/notifications/10/read"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 존재하지_않는_Notification은_404를_반환한다() throws Exception {
        when(notificationReadService.read(1L, 10L)).thenThrow(new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        mockMvc.perform(patch("/api/me/notifications/10/read"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void 타_사용자_Notification은_403을_반환한다() throws Exception {
        when(notificationReadService.read(1L, 10L)).thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(patch("/api/me/notifications/10/read"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private NotificationSummary summary(Instant readAt) {
        return new NotificationSummary(1L, new NotificationSummary.EventInfo(10L, "팬미팅 이벤트"),
                new NotificationSummary.DrawingInfo(20L, 0, DrawingType.INITIAL), NotificationType.INITIAL_WINNER,
                "당첨 안내", "축하합니다.", Instant.parse("2026-09-17T00:00:00Z"), readAt);
    }
}
