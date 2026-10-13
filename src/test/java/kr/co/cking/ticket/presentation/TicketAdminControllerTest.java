package kr.co.cking.ticket.presentation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.ticket.application.TicketAdminService;
import kr.co.cking.ticket.application.dto.AdminTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
import kr.co.cking.ticket.domain.TicketErrorCode;
import kr.co.cking.ticket.domain.CouponType;

@WebMvcTest(TicketAdminController.class)
@WithMockJwt(memberId = "1")
class TicketAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketAdminService ticketAdminService;

    private static final String BODY = """
            {"memberId":2,"creatorId":3,"reason":"정합성 배치 지속 불일치"}
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
                                {"memberId":2,"creatorId":3,"reason":""}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ticketAdminService);
    }

    /** ticket_ledger.reason은 VARCHAR(500) — 그보다 길면 DB 저장 단계가 아니라 요청 검증에서 막는다. */
    @Test
    void reason이_500자를_넘으면_서비스_호출_없이_400이다() throws Exception {
        String tooLong = "가".repeat(501);

        mockMvc.perform(post("/api/admin/tickets/resync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":2,\"creatorId\":3,\"reason\":\"%s\"}".formatted(tooLong)))
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

    @Test
    void 공용_재동기화_성공시_현재_잔액을_반환한다() throws Exception {
        when(ticketAdminService.resyncCommon(1L, 2L, "정합성 배치 지속 불일치"))
                .thenReturn(new CommonTicketBalanceResponse(2L, 10L, Instant.parse("2026-09-25T00:00:00Z")));

        mockMvc.perform(post("/api/admin/tickets/common/resync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":2,\"reason\":\"정합성 배치 지속 불일치\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(10));
    }

    @Test
    void 공용_재동기화는_reason이_비면_400이다() throws Exception {
        mockMvc.perform(post("/api/admin/tickets/common/resync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":2,\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ticketAdminService);
    }

    /** 관리자 잔액 조회가 대상 ID와 종류를 서비스에 전달하고 관측값을 응답하는지 검증한다. */
    @Test
    void 관리자_응모권_잔액을_조회한다() throws Exception {
        when(ticketAdminService.getBalance(1L, 2L, CouponType.CREATOR, 3L))
                .thenReturn(new AdminTicketBalanceResponse(2L, 3L, CouponType.CREATOR,
                        8L, 5L, true, true, 3L, Instant.parse("2026-10-13T01:00:00Z")));

        mockMvc.perform(get("/api/admin/tickets/balance")
                        .param("memberId", "2").param("couponType", "CREATOR").param("creatorId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(2))
                .andExpect(jsonPath("$.data.redisBalance").value(8))
                .andExpect(jsonPath("$.data.mysqlBalance").value(5))
                .andExpect(jsonPath("$.data.balanceDifference").value(3));
    }

    /** 공용 잔액 조회에서 MySQL 행 미존재를 오류가 아닌 응답 필드로 구분하는지 검증한다. */
    @Test
    void 공용_잔액의_MySQL_행_미존재를_표시한다() throws Exception {
        when(ticketAdminService.getBalance(1L, 2L, CouponType.COMMON, null))
                .thenReturn(new AdminTicketBalanceResponse(2L, null, CouponType.COMMON,
                        4L, null, true, false, null, Instant.parse("2026-10-13T01:00:00Z")));

        mockMvc.perform(get("/api/admin/tickets/balance")
                        .param("memberId", "2").param("couponType", "COMMON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creatorId").isEmpty())
                .andExpect(jsonPath("$.data.mysqlBalanceRowExists").value(false))
                .andExpect(jsonPath("$.data.mysqlBalance").isEmpty())
                .andExpect(jsonPath("$.data.balanceDifference").isEmpty())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"creatorId\":null", "\"mysqlBalance\":null", "\"balanceDifference\":null"));
    }

    /** 필수값·종류·양수 ID를 컨트롤러에서 거부하는지 검증한다. */
    @Test
    void 잔액_조회_입력_오류는_400이다() throws Exception {
        mockMvc.perform(get("/api/admin/tickets/balance").param("couponType", "COMMON"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/tickets/balance")
                        .param("memberId", "0").param("couponType", "COMMON"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/tickets/balance")
                        .param("memberId", "2").param("couponType", "OTHER"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ticketAdminService);
    }

    /** Redis 조회 실패를 키 미존재와 다른 503 오류로 전달하는지 검증한다. */
    @Test
    void Redis_조회_실패는_503이다() throws Exception {
        when(ticketAdminService.getBalance(1L, 2L, CouponType.COMMON, null))
                .thenThrow(new BusinessException(TicketErrorCode.REDIS_BALANCE_QUERY_FAILED));

        mockMvc.perform(get("/api/admin/tickets/balance")
                        .param("memberId", "2").param("couponType", "COMMON"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REDIS_BALANCE_QUERY_FAILED"));
    }
}
