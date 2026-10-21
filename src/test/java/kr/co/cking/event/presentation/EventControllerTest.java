package kr.co.cking.event.presentation;

import kr.co.cking.event.application.EventQueryService;
import kr.co.cking.event.application.dto.EventDetail;
import kr.co.cking.event.application.dto.EventSummary;
import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.EventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
@kr.co.cking.common.security.WithMockJwt(memberId = "100")
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventQueryService eventQueryService;

    @Test
    void 이벤트_목록조회는_요약_배열을_반환한다() throws Exception {
        EventSummary summary = new EventSummary(1L, 2L, "여름 이벤트",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"),
                EventStatus.OPEN, DisplayStatus.IN_PROGRESS, 3, "WEIGHTED");
        when(eventQueryService.getEvents(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andDo(r -> org.mockito.Mockito.verify(eventQueryService)
                        .getEvents(any(), any(), org.mockito.ArgumentMatchers.eq(100L), anyInt(), anyInt()))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].title").value("여름 이벤트"))
                .andExpect(jsonPath("$.data.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data.items[0].drawMethod").value("WEIGHTED"))
                .andExpect(jsonPath("$.data.items[0].displayStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 상태별_개수는_인증_없이_조회하고_한_번에_반환한다() throws Exception {
        when(eventQueryService.getEventCounts())
                .thenReturn(new kr.co.cking.event.application.dto.EventCounts(46, 5, 28, 13));

        mockMvc.perform(get("/api/events/counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(46))
                .andExpect(jsonPath("$.data.upcoming").value(5))
                .andExpect(jsonPath("$.data.inProgress").value(28))
                .andExpect(jsonPath("$.data.closed").value(13));
    }

    @Test
    void page가_음수면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/events").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void size가_0이면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/events").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void size가_100을_넘으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/events").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 이벤트_상세조회는_내_잔액을_포함한다() throws Exception {
        EventDetail detail = new EventDetail(1L, 2L, "여름 이벤트", "설명",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"),
                EventStatus.OPEN, DisplayStatus.IN_PROGRESS, 3, "WEIGHTED", 42L, 9L);
        when(eventQueryService.getEvent(1L, 100L)).thenReturn(detail);

        mockMvc.perform(get("/api/events/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.drawMethod").value("WEIGHTED"))
                .andExpect(jsonPath("$.data.myTicketBalance").value(42))
                .andExpect(jsonPath("$.data.myCommonTicketBalance").value(9));
    }

    @Test
    void 수동_마감된_이벤트_목록과_상세는_마감_요청_시각을_내려준다() throws Exception {
        Instant closeRequestedAt = Instant.parse("2026-09-15T03:00:00Z");
        Instant startAt = Instant.parse("2026-09-01T00:00:00Z");
        Instant endAt = Instant.parse("2026-09-30T00:00:00Z");
        EventSummary summary = new EventSummary(1L, 2L, "여름 이벤트", startAt, endAt, EventStatus.CLOSED,
                DisplayStatus.CLOSED, 3, "WEIGHTED", "PRIZE_WEIGHTED_V1", List.of(), null, closeRequestedAt);
        EventDetail detail = new EventDetail(1L, 2L, "여름 이벤트", "설명", startAt, endAt, EventStatus.CLOSED,
                DisplayStatus.CLOSED, 3, "WEIGHTED", "PRIZE_WEIGHTED_V1", 0L, 0L, List.of(), closeRequestedAt);
        when(eventQueryService.getEvents(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 10), 1));
        when(eventQueryService.getEvent(1L, 100L)).thenReturn(detail);

        mockMvc.perform(get("/api/events"))
                .andExpect(jsonPath("$.data.items[0].closeRequestedAt").value("2026-09-15T03:00:00Z"));
        mockMvc.perform(get("/api/events/1"))
                .andExpect(jsonPath("$.data.closeRequestedAt").value("2026-09-15T03:00:00Z"));
    }

    @Test
    void 마감되지_않은_이벤트의_마감_요청_시각은_null이다() throws Exception {
        EventSummary summary = new EventSummary(1L, 2L, "여름 이벤트",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"),
                EventStatus.OPEN, DisplayStatus.IN_PROGRESS, 3, "WEIGHTED");
        when(eventQueryService.getEvents(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/events"))
                .andExpect(jsonPath("$.data.items[0].closeRequestedAt").value(org.hamcrest.Matchers.nullValue()));
    }

    /** 인증 없이 상세를 조회하면 사용자 식별자 없이 서비스에 전달하고 잔액 0을 응답한다. */
    @Test
    @WithAnonymousUser
    void 비로그인_이벤트_상세조회는_잔액_0을_반환한다() throws Exception {
        EventDetail detail = new EventDetail(1L, 2L, "여름 이벤트", "설명",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"),
                EventStatus.OPEN, DisplayStatus.IN_PROGRESS, 3, "WEIGHTED", 0L, 0L);
        when(eventQueryService.getEvent(1L, null)).thenReturn(detail);

        mockMvc.perform(get("/api/events/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myTicketBalance").value(0))
                .andExpect(jsonPath("$.data.myCommonTicketBalance").value(0));
        verify(eventQueryService).getEvent(1L, null);
    }
}
