package kr.co.cking.ticket.presentation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import kr.co.cking.ticket.application.TicketQueryService;
import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
import kr.co.cking.ticket.application.dto.TicketLedgerItemResponse;
import kr.co.cking.ticket.application.dto.TicketLedgerPage;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TicketQueryController.class)
@kr.co.cking.common.security.WithMockJwt
class TicketQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketQueryService ticketQueryService;

    @Test
    void creator별_balance를_반환한다() throws Exception {
        when(ticketQueryService.getBalance(2L, 1L))
                .thenReturn(new TicketBalanceResponse(1L, 2L, 15L, Instant.parse("2026-09-16T00:00:00Z")));

        mockMvc.perform(get("/api/creators/2/tickets").queryParam("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.creatorId").value(2))
                .andExpect(jsonPath("$.data.balance").value(15));
    }

    @Test
    void creator별_ledger와_cursor를_반환한다() throws Exception {
        TicketLedgerItemResponse item = new TicketLedgerItemResponse(
                10L, 3L, "EARN", 5L, null, "출석", "request-1",
                Instant.parse("2026-09-16T02:00:00Z"));
        when(ticketQueryService.getLedger(2L, 1L, 20, null))
                .thenReturn(new TicketLedgerPage(1L, 2L, List.of(item), null, false));

        mockMvc.perform(get("/api/creators/2/tickets/history").queryParam("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].ledgerId").value(10))
                .andExpect(jsonPath("$.data.items[0].deltaAmount").value(3))
                .andExpect(jsonPath("$.data.items[0].type").value("EARN"))
                .andExpect(jsonPath("$.data.items[0].missionId").value(5))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.creatorId").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void size가_범위를_벗어나면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/creators/2/tickets/history")
                        .queryParam("userId", "1")
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
