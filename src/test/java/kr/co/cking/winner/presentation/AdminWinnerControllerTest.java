package kr.co.cking.winner.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.winner.application.AdminWinnerReceiveService;
import kr.co.cking.winner.domain.WinnerErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 관리자 Winner 수령 완료 HTTP 경계의 입력 검증과 오류 응답을 검증한다. */
@WebMvcTest(AdminWinnerController.class)
class AdminWinnerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminWinnerReceiveService adminWinnerReceiveService;

    @Test
    /** 유효한 관리자 수령 완료 요청은 공통 성공 응답을 반환한다. */
    void 관리자_Winner_수령_완료를_성공_응답으로_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/winners/100/receive")
                        .contentType("application/json")
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    /** 요청 본문에 관리자 userId가 없으면 입력 검증 오류를 반환한다. */
    void 수령_완료_요청의_userId가_누락되면_입력_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/winners/100/receive")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    /** 요청 본문의 관리자 userId가 양수가 아니면 입력 검증 오류를 반환한다. */
    void 수령_완료_요청의_userId가_0이면_입력_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/winners/100/receive")
                        .contentType("application/json")
                        .content("{\"userId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    /** 대상 winnerId가 양수가 아니면 Controller 경계에서 입력 검증 오류를 반환한다. */
    void 수령_완료_대상_winnerId가_0이면_입력_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/winners/0/receive")
                        .contentType("application/json")
                        .content("{\"userId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    /** 관리자 역할이 아닌 호출자는 권한 없음 응답을 반환한다. */
    void 관리자가_아닌_호출자는_FORBIDDEN_응답을_반환한다() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(adminWinnerReceiveService).receive(100L, 2L);

        mockMvc.perform(post("/api/admin/winners/100/receive")
                        .contentType("application/json")
                        .content("{\"userId\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    /** 이미 수령 완료된 Winner는 상태 충돌 응답을 반환한다. */
    void RECEIVED_종결_상태_Winner의_수령_완료는_CONFLICT_응답을_반환한다() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(WinnerErrorCode.INVALID_STATE))
                .when(adminWinnerReceiveService).receive(100L, 1L);

        mockMvc.perform(post("/api/admin/winners/100/receive")
                        .contentType("application/json")
                        .content("{\"userId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    /** 존재하지 않는 Winner는 도메인 Not Found 응답을 반환한다. */
    void 수령_완료_대상_Winner가_없으면_도메인_오류를_반환한다() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(WinnerErrorCode.WINNER_NOT_FOUND))
                .when(adminWinnerReceiveService).receive(999L, 1L);

        mockMvc.perform(post("/api/admin/winners/999/receive")
                        .contentType("application/json")
                        .content("{\"userId\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WINNER_NOT_FOUND"));
    }
}
