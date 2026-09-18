package kr.co.cking.winner.presentation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.winner.application.PublicWinnerQueryResult;
import kr.co.cking.winner.application.PublicWinnerQueryService;
import kr.co.cking.winner.application.PublicWinnerResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicWinnerController.class)
class PublicWinnerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicWinnerQueryService publicWinnerQueryService;

    @Test
    void 공개_Winner를_공통_성공_응답으로_반환한다() throws Exception {
        when(publicWinnerQueryService.getPublicWinners(10L)).thenReturn(new PublicWinnerQueryResult(10L, List.of(
                new PublicWinnerResult(100L, 20L, 0, DrawingType.INITIAL, "권*준", "010-****-5678", 1),
                new PublicWinnerResult(101L, 21L, 1, DrawingType.REDRAW, "김*지", "010-****-5432", 1)
        )));

        mockMvc.perform(get("/api/events/10/winners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(10))
                .andExpect(jsonPath("$.data.winners[0].winnerId").value(100))
                .andExpect(jsonPath("$.data.winners[0].drawNo").value(0))
                .andExpect(jsonPath("$.data.winners[0].drawType").value("INITIAL"))
                .andExpect(jsonPath("$.data.winners[0].name").value("권*준"))
                .andExpect(jsonPath("$.data.winners[0].phone").value("010-****-5678"))
                .andExpect(jsonPath("$.data.winners[1].drawingId").value(21))
                .andExpect(jsonPath("$.data.winners[1].drawNo").value(1))
                .andExpect(jsonPath("$.data.winners[1].drawType").value("REDRAW"));
    }

    @Test
    void 존재하지_않는_Event는_리소스_없음_응답을_반환한다() throws Exception {
        when(publicWinnerQueryService.getPublicWinners(999L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/events/999/winners"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void eventId가_양수가_아니면_입력_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(get("/api/events/0/winners"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
