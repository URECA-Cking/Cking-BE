package kr.co.cking.winner.presentation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.winner.application.MyWinnerQueryService;
import kr.co.cking.winner.application.MyWinnerResult;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyWinnerController.class)
class MyWinnerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyWinnerQueryService myWinnerQueryService;

    @Test
    void 내_INITIAL과_REDRAW_Winner를_공통_성공_응답으로_반환한다() throws Exception {
        when(myWinnerQueryService.getMyWinners(2L)).thenReturn(List.of(
                winner(100L, 20L, 0, DrawingType.INITIAL, WinnerManagementStatus.SELECTED),
                winner(101L, 21L, 1, DrawingType.REDRAW, WinnerManagementStatus.RECEIVED)
        ));

        mockMvc.perform(get("/api/me/winners").param("userId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].winnerId").value(100))
                .andExpect(jsonPath("$.data[0].eventId").value(10))
                .andExpect(jsonPath("$.data[0].drawType").value("INITIAL"))
                .andExpect(jsonPath("$.data[0].appliedTicketCount").value(3))
                .andExpect(jsonPath("$.data[0].winnerManagementStatus").value("SELECTED"))
                .andExpect(jsonPath("$.data[1].drawType").value("REDRAW"))
                .andExpect(jsonPath("$.data[1].winnerManagementStatus").value("RECEIVED"));
    }

    @Test
    void 존재하지_않는_Member는_리소스_없음_응답을_반환한다() throws Exception {
        when(myWinnerQueryService.getMyWinners(999L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/me/winners").param("userId", "999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void userId가_누락되면_입력_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/winners"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void userId가_양수가_아니면_입력_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/winners").param("userId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** Controller 응답 검증에 필요한 당첨 결과를 만든다. */
    private MyWinnerResult winner(
            Long winnerId,
            Long drawingId,
            int drawNo,
            DrawingType drawType,
            WinnerManagementStatus winnerManagementStatus
    ) {
        Instant createdAt = Instant.parse("2026-09-19T10:00:00Z");
        return new MyWinnerResult(
                winnerId,
                10L,
                drawingId,
                drawNo,
                drawType,
                1,
                3L,
                createdAt,
                300L,
                winnerManagementStatus,
                createdAt,
                createdAt
        );
    }
}
