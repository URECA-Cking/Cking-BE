package kr.co.cking.notification.presentation;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.notification.application.NotificationQueryService;
import kr.co.cking.notification.application.dto.NotificationSummary;
import kr.co.cking.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationQueryService notificationQueryService;

    @Test
    void 내_알림_목록은_페이지_응답과_알림_상세를_반환한다() throws Exception {
        NotificationSummary summary = new NotificationSummary(
                1L,
                new NotificationSummary.EventInfo(10L, "팬미팅 이벤트"),
                new NotificationSummary.DrawingInfo(20L, 0, DrawingType.INITIAL),
                NotificationType.INITIAL_WINNER,
                "당첨 안내",
                "축하합니다.",
                Instant.parse("2026-09-17T00:00:00Z"),
                null
        );
        when(notificationQueryService.findMine(eq(1L), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/me/notifications").param("userId", "1"))
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
        mockMvc.perform(get("/api/me/notifications").param("userId", "1").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 읽은_알림의_readAt은_UTC_시각으로_반환한다() throws Exception {
        Instant readAt = Instant.parse("2026-09-17T01:00:00Z");
        NotificationSummary summary = new NotificationSummary(
                1L,
                new NotificationSummary.EventInfo(10L, "팬미팅 이벤트"),
                new NotificationSummary.DrawingInfo(20L, 0, DrawingType.INITIAL),
                NotificationType.INITIAL_WINNER,
                "당첨 안내",
                "축하합니다.",
                Instant.parse("2026-09-17T00:00:00Z"),
                readAt
        );
        when(notificationQueryService.findMine(eq(1L), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/me/notifications").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].readAt").value("2026-09-17T01:00:00Z"));
    }

    @Test
    void size가_0이면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/notifications").param("userId", "1").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void size가_100을_넘으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/notifications").param("userId", "1").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void userId가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/notifications"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
