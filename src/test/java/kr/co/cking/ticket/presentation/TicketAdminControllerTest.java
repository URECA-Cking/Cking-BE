package kr.co.cking.ticket.presentation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.ticket.application.TicketAdminService;
import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
import kr.co.cking.ticket.domain.TicketErrorCode;

@WebMvcTest(TicketAdminController.class)
class TicketAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketAdminService ticketAdminService;

    private static final String BODY = """
            {"userId":1,"memberId":2,"creatorId":3,"reason":"정합성 배치 지속 불일치"}
            """;

    @Test
    void 재동기화_성공시_현재_잔액을_반환한다() throws Exception {
        when(ticketAdminService.resync(1L, 2L, 3L, "정합성 배치 지속 불일치"))
                .thenReturn(new TicketBalanceResponse(2L, 3L, 10L, Instant.parse("2026-09-22T00:00:00Z")));

        mockMvc.perform(post("/api/admin/tickets/resync")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(10));
    }

    @Test
    void 필수값이_없으면_서비스_호출_없이_400이다() throws Exception {
        mockMvc.perform(post("/api/admin/tickets/resync")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/tickets/resync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"memberId":2,"creatorId":3,"reason":""}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ticketAdminService);
    }

    @Test
    void ADMIN이_아니면_403이고_보정중_충돌이면_409다() throws Exception {
        when(ticketAdminService.resync(eq(1L), eq(2L), eq(3L), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(post("/api/admin/tickets/resync")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        when(ticketAdminService.resync(eq(1L), eq(2L), eq(3L), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new BusinessException(TicketErrorCode.CONCURRENT_COMMAND));

        mockMvc.perform(post("/api/admin/tickets/resync")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void 미반영_메시지가_있으면_409다() throws Exception {
        when(ticketAdminService.resync(eq(1L), eq(2L), eq(3L), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new BusinessException(TicketErrorCode.INVALID_STATE));

        mockMvc.perform(post("/api/admin/tickets/resync")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void 대상_Balance가_없으면_404다() throws Exception {
        when(ticketAdminService.resync(eq(1L), eq(2L), eq(3L), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(post("/api/admin/tickets/resync")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }
}
