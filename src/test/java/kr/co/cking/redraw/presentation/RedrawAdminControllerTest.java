package kr.co.cking.redraw.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.redraw.application.RedrawRequestCreateCommand;
import kr.co.cking.redraw.application.RedrawRequestCreateResult;
import kr.co.cking.redraw.application.RedrawRequestCreateService;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 관리자 RedrawRequest 생성 API의 성공·멱등·입력 검증 응답을 검증한다. */
@WebMvcTest(RedrawAdminController.class)
class RedrawAdminControllerTest {

    private static final String IDEMPOTENCY_KEY = "d2719c4a-1f9b-4dc4-a656-9a4bb37d8e70";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RedrawRequestCreateService redrawRequestCreateService;

    /** 새 RedrawRequest는 서버가 결정한 원본·결원 정보를 201 응답으로 반환한다. */
    @Test
    void 새_RedrawRequest는_CREATED_응답을_반환한다() throws Exception {
        when(redrawRequestCreateService.create(any(RedrawRequestCreateCommand.class))).thenReturn(result(true));

        mockMvc.perform(post("/api/admin/events/10/redraw-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.redrawRequestId").value(30))
                .andExpect(jsonPath("$.data.eventId").value(10))
                .andExpect(jsonPath("$.data.originalDrawingId").value(20))
                .andExpect(jsonPath("$.data.vacancyCount").value(2))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.executionStatus").value("PENDING"));
        verify(redrawRequestCreateService).create(new RedrawRequestCreateCommand(
                1L, 10L, "당첨자 포기에 따른 재추첨", IDEMPOTENCY_KEY
        ));
    }

    /** 같은 멱등 키의 기존 요청은 현재 상태를 200 응답으로 반환한다. */
    @Test
    void 멱등_재요청은_OK_응답을_반환한다() throws Exception {
        when(redrawRequestCreateService.create(any(RedrawRequestCreateCommand.class))).thenReturn(result(false));

        mockMvc.perform(post("/api/admin/events/10/redraw-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.redrawRequestId").value(30));
    }

    /** userId·사유·idempotencyKey가 누락되거나 형식이 틀리면 Service 호출 전에 차단한다. */
    @Test
    void 유효하지_않은_요청은_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/events/0/redraw-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":0,\"reason\":\" \",\"idempotencyKey\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 클라이언트가 서버 결정 값인 결원 수나 원본 Drawing ID를 보내면 요청을 거부한다. */
    @Test
    void 서버_결정_필드를_전달하면_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/events/10/redraw-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"reason\":\"사유\",\"idempotencyKey\":\""
                                + IDEMPOTENCY_KEY + "\",\"vacancyCount\":2,\"originalDrawingId\":20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 테스트 요청 JSON은 클라이언트 입력에 허용한 세 필드만 담는다. */
    private String requestBody() {
        return "{\"userId\":1,\"reason\":\"당첨자 포기에 따른 재추첨\",\"idempotencyKey\":\""
                + IDEMPOTENCY_KEY + "\"}";
    }

    /** 테스트용 생성 또는 멱등 재요청 결과를 만든다. */
    private RedrawRequestCreateResult result(boolean created) {
        return new RedrawRequestCreateResult(
                30L, 10L, 20L, 2, RedrawRequestStatus.REQUESTED, RedrawExecutionStatus.PENDING, created
        );
    }
}
