package kr.co.cking.mission.presentation;

import kr.co.cking.mission.application.WelcomeQuestService;
import kr.co.cking.mission.application.dto.WelcomeQuestClaimOutcome;
import kr.co.cking.mission.application.dto.WelcomeQuestItem;
import kr.co.cking.mission.application.dto.WelcomeQuestState;
import kr.co.cking.mission.domain.WelcomeQuestStatus;
import kr.co.cking.mission.domain.WelcomeQuestStep;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 인증·입력 형식·응답 봉투의 외부 API 계약을 검증한다. */
@WebMvcTest(WelcomeQuestController.class)
@kr.co.cking.common.security.WithMockJwt
class WelcomeQuestControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private WelcomeQuestService service;

    /** 조회는 대상 상태와 독립 보너스를 공통 봉투로 반환한다. */
    @Test
    void 상태_조회는_보너스를_별도_필드로_반환한다() throws Exception {
        when(service.findState(any())).thenReturn(new WelcomeQuestState(true, false,
                List.of(new WelcomeQuestItem(WelcomeQuestStep.SIGNUP, 1, WelcomeQuestStatus.CLAIMABLE)),
                new WelcomeQuestItem(WelcomeQuestStep.BONUS, 1, WelcomeQuestStatus.CLAIMED)));
        mvc.perform(get("/api/me/welcome-quest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(true))
                .andExpect(jsonPath("$.data.bonus.step").value("BONUS"));
    }

    /** 새 지급은 202, 동일 requestId 재시도는 200으로 구분한다. */
    @Test
    void 지급_승인과_재시도를_HTTP_상태로_구분한다() throws Exception {
        UUID id = UUID.randomUUID();
        WelcomeQuestItem item = new WelcomeQuestItem(WelcomeQuestStep.SIGNUP, 1, WelcomeQuestStatus.CLAIMED);
        when(service.claim(eq(1L), eq(WelcomeQuestStep.SIGNUP), eq(id), eq(null)))
                .thenReturn(new WelcomeQuestClaimOutcome(EarnResultCode.EARN_ACCEPTED, item))
                .thenReturn(new WelcomeQuestClaimOutcome(EarnResultCode.ALREADY_PROCESSED, item));
        String body = "{\"requestId\":\"" + id + "\"}";
        mvc.perform(post("/api/me/welcome-quest/SIGNUP/claim").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value("EARN_ACCEPTED"));
        mvc.perform(post("/api/me/welcome-quest/SIGNUP/claim").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("ALREADY_PROCESSED"));
    }

    /** 지원하지 않는 단계와 누락된 UUID는 Service 전에 400으로 거절한다. */
    @Test
    void 잘못된_단계와_UUID는_400으로_거절한다() throws Exception {
        mvc.perform(post("/api/me/welcome-quest/OTHER/claim")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/me/welcome-quest/QUIZ/claim")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    /** JWT가 없으면 공통 인증 오류를 반환한다. */
    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    void 인증_없이_조회와_지급을_호출할_수_없다() throws Exception {
        mvc.perform(get("/api/me/welcome-quest"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/me/welcome-quest/SIGNUP/claim")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
