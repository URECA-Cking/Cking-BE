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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
}
