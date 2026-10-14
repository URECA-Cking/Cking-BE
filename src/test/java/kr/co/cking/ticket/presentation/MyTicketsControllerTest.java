package kr.co.cking.ticket.presentation;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.ticket.application.MyTicketsSummaryService;
import kr.co.cking.ticket.application.dto.MyTicketsSummaryResponse;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MyTicketsController.class)
class MyTicketsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyTicketsSummaryService myTicketsSummaryService;

    @Test
    @WithMockJwt(memberId = "7")
    void 로그인한_회원의_응모권_요약을_반환한다() throws Exception {
        given(myTicketsSummaryService.getSummary(7L)).willReturn(new MyTicketsSummaryResponse(
                new MyTicketsSummaryResponse.CommonBalance(3L, Instant.parse("2026-10-01T00:00:00Z")),
                List.of(new MyTicketsSummaryResponse.CreatorTicket(
                        1L, "크리에이터", "https://img/1", 4L, Instant.parse("2026-10-02T00:00:00Z"))),
                4L,
                7L));

        mockMvc.perform(get("/api/me/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.common.balance").value(3))
                .andExpect(jsonPath("$.data.creators[0].creatorId").value(1))
                .andExpect(jsonPath("$.data.creators[0].creatorName").value("크리에이터"))
                .andExpect(jsonPath("$.data.creators[0].profileImageUrl").value("https://img/1"))
                .andExpect(jsonPath("$.data.creators[0].balance").value(4))
                .andExpect(jsonPath("$.data.creatorTotal").value(4))
                .andExpect(jsonPath("$.data.total").value(7));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 팔로우한_크리에이터가_없으면_빈_목록과_공용_잔액_합계를_반환한다() throws Exception {
        given(myTicketsSummaryService.getSummary(7L)).willReturn(new MyTicketsSummaryResponse(
                new MyTicketsSummaryResponse.CommonBalance(0L, null), List.of(), 0L, 0L));

        mockMvc.perform(get("/api/me/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creators").isEmpty())
                .andExpect(jsonPath("$.data.creatorTotal").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/me/tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
