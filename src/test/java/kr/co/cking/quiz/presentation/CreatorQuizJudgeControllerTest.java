package kr.co.cking.quiz.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.application.QuizJudgeEvaluationService;
import kr.co.cking.quiz.application.dto.QuizJudgeEvaluationView;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.judge.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CreatorQuizJudgeController.class)
class CreatorQuizJudgeControllerTest {
    private static final String BASE = "/api/creator/quizzes/1/revisions/100/judge-evaluations";
    private static final UUID REQUEST = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String BODY = "{\"requestId\":\"" + REQUEST + "\"}";
    @Autowired private MockMvc mvc;
    @MockitoBean private QuizJudgeEvaluationService service;
    @MockitoBean private MemberRepository memberRepository;

    @Test void 인증이_없으면_POST_GET_모두_401이다() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/40")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test @WithMockJwt(memberId = "7")
    void 요청과_replay는_202와_같은_Job_ID를_반환한다() throws Exception {
        given(service.request(7L, 1L, 100L, REQUEST)).willReturn(view(QuizJudgeJobStatus.PENDING, null));
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.evaluationId").value(40))
                    .andExpect(jsonPath("$.data.attempt").value(1));
        }
    }
    @Test @WithMockJwt(memberId = "7")
    void UUID_누락_null_잘못된_형식과_음수_ID는_400이다() throws Exception {
        for (String body : List.of("{}", "{\"requestId\":null}", "{\"requestId\":\"invalid\"}")) {
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(get(BASE + "/-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test @WithMockJwt(memberId = "7")
    void 완료된_문항의_7개_판정과_reason만_조회한다() throws Exception {
        given(service.find(7L, 1L, 100L, 40L)).willReturn(view(QuizJudgeJobStatus.SUCCEEDED, null));
        String json = mvc.perform(get(BASE + "/40")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aggregation").value("ALL_PASS"))
                .andExpect(jsonPath("$.data.questions[0].questionNo").value(0))
                .andExpect(jsonPath("$.data.questions[0].items.textAnswerCorrect.verdict").value("pass"))
                .andExpect(jsonPath("$.data.questions[0].items.textAnswerCorrect.reason").value("근거"))
                .andReturn().getResponse().getContentAsString();
        var data = new ObjectMapper().readTree(json).path("data");
        assertThat(data.path("questions").get(0).path("items").size()).isEqualTo(7);
        assertThat(data.properties().stream().map(Map.Entry::getKey).toList()).doesNotContain(
                "contentText", "apiKey", "usage", "responseId", "responseModel", "rawResponse", "settingsFingerprint");
    }
    @Test @WithMockJwt(memberId = "7")
    void Provider_실패는_HTTP_200과_안전한_상태로_반환한다() throws Exception {
        given(service.find(7L, 1L, 100L, 40L)).willReturn(view(QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.PROVIDER_TIMEOUT));
        mvc.perform(get(BASE + "/40")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.failureCode").value("PROVIDER_TIMEOUT"))
                .andExpect(jsonPath("$.data.questions").isEmpty());
    }
    @Test @WithMockJwt(memberId = "7")
    void Creator가_아니거나_타인_Quiz는_403이고_없는_리소스는_404다() throws Exception {
        given(service.request(7L, 1L, 100L, REQUEST)).willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        for (var error : List.of(QuizErrorCode.QUIZ_NOT_FOUND, QuizErrorCode.QUIZ_REVISION_NOT_FOUND,
                QuizErrorCode.QUIZ_JUDGE_EVALUATION_NOT_FOUND)) {
            willThrow(new BusinessException(error)).given(service).find(7L, 1L, 100L, 40L);
            mvc.perform(get(BASE + "/40")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(error.name()));
        }
    }
    @Test @WithMockJwt(memberId = "7")
    void 접수_충돌은_409이고_기능_OFF와_미설정은_503이다() throws Exception {
        for (var error : List.of(QuizErrorCode.QUIZ_JUDGE_REQUEST_CONFLICT, QuizErrorCode.QUIZ_JUDGE_ATTEMPT_LIMIT_REACHED,
                QuizErrorCode.QUIZ_JUDGE_EVALUATION_COMPLETED, QuizErrorCode.QUIZ_JUDGE_IN_PROGRESS,
                QuizErrorCode.QUIZ_JUDGE_SETTINGS_MISMATCH)) {
            willThrow(new BusinessException(error)).given(service).request(7L, 1L, 100L, REQUEST);
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(error.name()));
        }
        for (var error : List.of(QuizErrorCode.QUIZ_JUDGE_DISABLED, QuizErrorCode.QUIZ_JUDGE_NOT_CONFIGURED)) {
            willThrow(new BusinessException(error)).given(service).request(7L, 1L, 100L, REQUEST);
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isServiceUnavailable());
        }
    }
    private QuizJudgeEvaluationView view(QuizJudgeJobStatus status, QuizJudgeFailure failure) {
        Map<JudgeRubric, QuizJudgeEvaluation.RubricResult> items = new EnumMap<>(JudgeRubric.class);
        for (var rubric : JudgeRubric.values()) items.put(rubric, new QuizJudgeEvaluation.RubricResult(JudgeVerdict.PASS, "근거"));
        return new QuizJudgeEvaluationView(40L, 1L, 100L, status, 1, 2, failure,
                status == QuizJudgeJobStatus.SUCCEEDED ? 50L : null,
                status == QuizJudgeJobStatus.SUCCEEDED ? JudgeAggregation.ALL_PASS : null,
                Instant.EPOCH, null, null, status == QuizJudgeJobStatus.SUCCEEDED
                ? List.of(new QuizJudgeEvaluation.QuestionEvaluation(0, items)) : List.of());
    }

    @Test @WithMockJwt(memberId = "7")
    void 저장_평가_무결성_오류는_안전한_500_응답이다() throws Exception {
        given(service.find(7L, 1L, 100L, 40L)).willThrow(
                new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED));
        mvc.perform(get(BASE + "/40")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED"));
    }
}
