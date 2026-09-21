package kr.co.cking.winner.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.winner.application.AdminWinnerDisqualifyService;
import kr.co.cking.winner.application.AdminWinnerReceiveService;
import kr.co.cking.winner.domain.WinnerErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 관리자 Winner 수령 완료와 자격 박탈 HTTP 경계의 입력 검증과 오류 응답을 검증한다. */
@WebMvcTest(AdminWinnerController.class)
class AdminWinnerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminWinnerReceiveService adminWinnerReceiveService;

    @MockitoBean
    private AdminWinnerDisqualifyService adminWinnerDisqualifyService;

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

    /** 유효한 관리자 자격 박탈 요청은 공통 성공 응답을 반환한다. */
    @Test
    void 관리자_Winner_자격_박탈을_성공_응답으로_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/winners/100/disqualify")
                        .contentType("application/json")
                        .content("{\"userId\":1,\"reason\":\"참여 조건 미충족\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /** 요청 본문에 자격 박탈 사유가 없으면 입력 검증 오류를 반환한다. */
    @Test
    void 자격_박탈_요청의_reason이_누락되면_입력_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/winners/100/disqualify")
                        .contentType("application/json")
                        .content("{\"userId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 공백 또는 500자를 넘는 자격 박탈 사유는 입력 검증 오류를 반환한다. */
    @Test
    void 자격_박탈_요청의_reason이_유효하지_않으면_입력_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/winners/100/disqualify")
                        .contentType("application/json")
                        .content("{\"userId\":1,\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        String oversizedReason = "가".repeat(501);
        org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.VALIDATION_FAILED))
                .when(adminWinnerDisqualifyService).disqualify(100L, 1L, oversizedReason);
        mockMvc.perform(post("/api/admin/winners/100/disqualify")
                        .contentType("application/json")
                        .content("{\"userId\":1,\"reason\":\"" + oversizedReason + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 500자 사유에 앞뒤 공백이 있어도 Service 정규화 정책에 맡기고 요청을 통과시킨다. */
    @Test
    void 자격_박탈_요청의_사유는_정규화_후_500자면_통과한다() throws Exception {
        String reason = "  " + "가".repeat(500) + "  ";

        mockMvc.perform(post("/api/admin/winners/100/disqualify")
                        .contentType("application/json")
                        .content("{\"userId\":1,\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    /** 대상 winnerId가 양수가 아니면 자격 박탈 Controller 경계에서 입력 검증 오류를 반환한다. */
    @Test
    void 자격_박탈_대상_winnerId가_0이면_입력_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/winners/0/disqualify")
                        .contentType("application/json")
                        .content("{\"userId\":1,\"reason\":\"참여 조건 미충족\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 관리자 역할이 아닌 호출자는 자격 박탈 권한 없음 응답을 반환한다. */
    @Test
    void 자격_박탈_요청에서_관리자가_아닌_호출자는_FORBIDDEN_응답을_반환한다() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(adminWinnerDisqualifyService).disqualify(100L, 2L, "참여 조건 미충족");

        mockMvc.perform(post("/api/admin/winners/100/disqualify")
                        .contentType("application/json")
                        .content("{\"userId\":2,\"reason\":\"참여 조건 미충족\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 종결 상태 Winner의 자격 박탈은 상태 충돌 응답을 반환한다. */
    @Test
    void DISQUALIFIED_종결_상태_Winner의_자격_박탈은_CONFLICT_응답을_반환한다() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(WinnerErrorCode.INVALID_STATE))
                .when(adminWinnerDisqualifyService).disqualify(100L, 1L, "참여 조건 미충족");

        mockMvc.perform(post("/api/admin/winners/100/disqualify")
                        .contentType("application/json")
                        .content("{\"userId\":1,\"reason\":\"참여 조건 미충족\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }
}
