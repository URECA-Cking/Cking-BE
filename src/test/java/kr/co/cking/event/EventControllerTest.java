package kr.co.cking.event;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventQueryService eventQueryService;

    @Test
    void 이벤트_목록조회는_요약_배열을_반환한다() throws Exception {
        EventSummary summary = new EventSummary(1L, 2L, "여름 이벤트",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"),
                3, DisplayStatus.IN_PROGRESS);
        when(eventQueryService.getEvents(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].title").value("여름 이벤트"))
                .andExpect(jsonPath("$.data.content[0].displayStatus").value("IN_PROGRESS"));
    }

    @Test
    void 이벤트_상세조회는_내_잔액을_포함한다() throws Exception {
        EventDetail detail = new EventDetail(1L, 2L, "여름 이벤트", "설명",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"),
                3, DisplayStatus.IN_PROGRESS, 42L);
        when(eventQueryService.getEvent(1L, 100L)).thenReturn(detail);

        mockMvc.perform(get("/api/events/1").param("userId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myTicketBalance").value(42));
    }
}
