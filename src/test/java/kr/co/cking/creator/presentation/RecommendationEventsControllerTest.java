package kr.co.cking.creator.presentation;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.creator.application.RecommendationTrackingObserver;
import kr.co.cking.creator.application.RecommendationTrackingService;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RecommendationEventsController.class)
class RecommendationEventsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean RecommendationTrackingService service;
    @MockitoBean RecommendationTrackingObserver observer;
    @MockitoBean MemberRepository memberRepository;

    @Test
    @WithMockJwt(memberId = "7")
    void JWT_회원으로_최대_50건을_승인한다() throws Exception {
        given(service.collect(eq(7L), anyList())).willReturn(50);
        mvc.perform(post("/api/me/creator-recommendation-events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + String.join(",", Collections.nCopies(50, event())) + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.acceptedCount").value(50));
        verify(service).collect(eq(7L), anyList());
    }

    @Test
    void 인증이_없으면_수집하지_않는다() throws Exception {
        mvc.perform(post("/api/me/creator-recommendation-events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event() + "]}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"events\":null}", "{\"events\":[]}", "{\"events\":[null]}",
            "{\"events\":[{}]}", "{\"events\":[{\"eventId\":\"bad-uuid\"}]}"})
    @WithMockJwt(memberId = "7")
    void 누락과_빈_배치와_잘못된_UUID는_거부한다(String body) throws Exception {
        invalid(body);
    }

    @Test
    @WithMockJwt(memberId = "7")
    void FOLLOW_클라이언트_타입과_음수_ID와_초과_배치를_거부한다() throws Exception {
        invalid("{\"events\":[" + event().replace("CLICK", "FOLLOW") + "]}");
        invalid("{\"events\":[" + event().replace("\"creatorId\":20", "\"creatorId\":-1") + "]}");
        invalid("{\"events\":[" + String.join(",", Collections.nCopies(51, event())) + "]}");
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 업무_충돌과_저장_실패를_성공으로_숨기지_않는다() throws Exception {
        given(service.collect(eq(7L), anyList())).willThrow(new BusinessException(CreatorErrorCode.RECOMMENDATION_EVENT_CONFLICT));
        mvc.perform(post("/api/me/creator-recommendation-events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event() + "]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECOMMENDATION_EVENT_CONFLICT"));
        given(service.collect(eq(7L), anyList())).willThrow(new IllegalStateException("database unavailable"));
        mvc.perform(post("/api/me/creator-recommendation-events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event() + "]}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SYSTEM_ERROR"));
    }

    private void invalid(String body) throws Exception {
        mvc.perform(post("/api/me/creator-recommendation-events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    private String event() {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"recommendationRequestId\":\"" + UUID.randomUUID()
                + "\",\"creatorId\":20,\"eventType\":\"CLICK\"}";
    }
}
