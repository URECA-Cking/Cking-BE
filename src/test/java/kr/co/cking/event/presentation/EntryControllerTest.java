package kr.co.cking.event.presentation;

import tools.jackson.databind.ObjectMapper;
import kr.co.cking.event.application.EventEntryService;
import kr.co.cking.event.application.EventEntryQueryService;
import kr.co.cking.event.application.dto.EntryHistoryItemResponse;
import kr.co.cking.event.application.dto.EntryHistoryPage;
import kr.co.cking.event.application.dto.EntryOutcome;
import kr.co.cking.event.domain.EntryResultCode;
import kr.co.cking.event.presentation.dto.EntryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EntryController.class)
class EntryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EventEntryService eventEntryService;

    @MockitoBean
    private EventEntryQueryService eventEntryQueryService;

    @Test
    void 내_응모내역을_cursor_형식으로_반환한다() throws Exception {
        EntryHistoryItemResponse item = new EntryHistoryItemResponse(
                10L, 3L, Instant.parse("2026-09-18T02:00:00Z"));
        when(eventEntryQueryService.getMyEntries(2L, 1L, 20, null))
                .thenReturn(new EntryHistoryPage(List.of(item), null, false));

        mockMvc.perform(get("/api/events/2/entries/me").queryParam("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].entryId").value(10))
                .andExpect(jsonPath("$.data.items[0].usedTicketCount").value(3))
                .andExpect(jsonPath("$.data.items[0].appliedAt").value("2026-09-18T02:00:00Z"))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(eventEntryQueryService).getMyEntries(2L, 1L, 20, null);
    }

    @Test
    void 응모내역_size가_100을_초과하면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/events/2/entries/me")
                        .queryParam("userId", "1")
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 응모내역의_eventId와_userId는_양수여야_한다() throws Exception {
        mockMvc.perform(get("/api/events/0/entries/me").queryParam("userId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 정상_응모는_SUCCESS_코드와_accepted_true를_반환한다() throws Exception {
        UUID requestId = UUID.randomUUID();
        EntryRequest request = new EntryRequest(1L, requestId, 2);
        EntryOutcome outcome = new EntryOutcome(EntryResultCode.SUCCESS, requestId, 1L);
        when(eventEntryService.apply(eq(1L), any())).thenReturn(outcome);

        mockMvc.perform(post("/api/events/1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(1))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.entryId").doesNotExist());
    }

    @Test
    void ticketCount가_0이면_400을_반환한다() throws Exception {
        EntryRequest request = new EntryRequest(1L, UUID.randomUUID(), 0);

        mockMvc.perform(post("/api/events/1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void ticketCount가_100_초과면_400을_반환한다() throws Exception {
        EntryRequest request = new EntryRequest(1L, UUID.randomUUID(), 101);

        mockMvc.perform(post("/api/events/1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
