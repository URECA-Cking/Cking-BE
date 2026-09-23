package kr.co.cking.event.presentation;

import tools.jackson.databind.ObjectMapper;
import kr.co.cking.event.application.EntryStatusQueryService;
import kr.co.cking.event.application.EventEntryService;
import kr.co.cking.event.application.EventEntryQueryService;
import kr.co.cking.event.application.dto.EntryCommand;
import kr.co.cking.event.application.dto.EntryHistoryItemResponse;
import kr.co.cking.event.application.dto.EntryHistoryPage;
import kr.co.cking.event.application.dto.EntryOutcome;
import kr.co.cking.event.application.dto.EntryStatusResponse;
import kr.co.cking.event.domain.EntryResultCode;
import kr.co.cking.event.presentation.dto.EntryRequest;
import kr.co.cking.ticket.domain.CouponType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EntryController.class)
@kr.co.cking.common.security.WithMockJwt
class EntryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EventEntryService eventEntryService;

    @MockitoBean
    private EventEntryQueryService eventEntryQueryService;

    @MockitoBean
    private EntryStatusQueryService entryStatusQueryService;


    @Test
    void 내_응모내역을_cursor_형식으로_반환한다() throws Exception {
        EntryHistoryItemResponse item = new EntryHistoryItemResponse(
                10L, 3L, Instant.parse("2026-09-18T02:00:00Z"));
        when(eventEntryQueryService.getMyEntries(2L, 1L, 20, null))
                .thenReturn(new EntryHistoryPage(List.of(item), null, false));

        mockMvc.perform(get("/api/events/2/entries/me"))
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
    @kr.co.cking.common.security.WithMockJwt(memberId = "7")
    void 실시간_응모_현황을_조회한다() throws Exception {
        when(entryStatusQueryService.getStatus(2L, 7L))
                .thenReturn(new EntryStatusResponse(2L, 3L, 7L, 4L, true));

        mockMvc.perform(get("/api/events/2/entry-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(2))
                .andExpect(jsonPath("$.data.participantCount").value(3))
                .andExpect(jsonPath("$.data.totalTicketCount").value(7))
                .andExpect(jsonPath("$.data.myTicketCount").value(4))
                .andExpect(jsonPath("$.data.realtime").value(true));

        verify(entryStatusQueryService).getStatus(2L, 7L);
    }

    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    void JWT가_없으면_실시간_응모_현황은_401과_UNAUTHORIZED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/events/2/entry-status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 응모_현황_eventId는_양수여야_한다() throws Exception {
        mockMvc.perform(get("/api/events/0/entry-status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 응모내역_size가_100을_초과하면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/events/2/entries/me")
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 응모내역의_eventId는_양수여야_한다() throws Exception {
        mockMvc.perform(get("/api/events/0/entries/me"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 정상_응모는_SUCCESS_코드와_accepted_true를_반환한다() throws Exception {
        UUID requestId = UUID.randomUUID();
        EntryRequest request = new EntryRequest(requestId, 2, null);
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
        EntryRequest request = new EntryRequest(UUID.randomUUID(), 0, null);

        mockMvc.perform(post("/api/events/1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void ticketCount가_100_초과면_400을_반환한다() throws Exception {
        EntryRequest request = new EntryRequest(UUID.randomUUID(), 101, null);

        mockMvc.perform(post("/api/events/1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 이슈 #243: couponType을 생략하면 EntryCommand에 CREATOR로 확정돼 넘어가야 한다.
    @Test
    void couponType을_생략하면_CREATOR로_확정해서_전달한다() throws Exception {
        EntryRequest request = new EntryRequest(UUID.randomUUID(), 2, null);
        when(eventEntryService.apply(eq(1L), any()))
                .thenReturn(new EntryOutcome(EntryResultCode.SUCCESS, request.requestId(), 1L));

        mockMvc.perform(post("/api/events/1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        ArgumentCaptor<EntryCommand> captor = ArgumentCaptor.forClass(EntryCommand.class);
        verify(eventEntryService).apply(eq(1L), captor.capture());
        assertThat(captor.getValue().couponType()).isEqualTo(CouponType.CREATOR);
    }

    @Test
    void couponType_COMMON을_보내면_그대로_전달한다() throws Exception {
        EntryRequest request = new EntryRequest(UUID.randomUUID(), 2, CouponType.COMMON);
        when(eventEntryService.apply(eq(1L), any()))
                .thenReturn(new EntryOutcome(EntryResultCode.SUCCESS, request.requestId(), 1L));

        mockMvc.perform(post("/api/events/1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        ArgumentCaptor<EntryCommand> captor = ArgumentCaptor.forClass(EntryCommand.class);
        verify(eventEntryService).apply(eq(1L), captor.capture());
        assertThat(captor.getValue().couponType()).isEqualTo(CouponType.COMMON);
    }

    @Test
    void couponType이_CREATOR_COMMON_외의_값이면_400을_반환한다() throws Exception {
        String invalidBody = """
                {"requestId":"%s","ticketCount":2,"couponType":"INVALID"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/events/1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
