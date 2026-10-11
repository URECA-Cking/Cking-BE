package kr.co.cking.quiz.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.CreatorQuizCommandService;
import kr.co.cking.quiz.application.CreatorQuizQueryService;
import kr.co.cking.quiz.application.dto.CreatorQuizViews;
import kr.co.cking.quiz.application.dto.QuizDecisionView;
import kr.co.cking.quiz.application.dto.QuizPublicationView;
import kr.co.cking.quiz.application.dto.QuizQuestionContent;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizPublicationStatus;
import kr.co.cking.quiz.domain.QuizReviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorQuizController.class)
class CreatorQuizControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorQuizQueryService queryService;

    @MockitoBean
    private CreatorQuizCommandService commandService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/creator/quizzes"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/creator/quizzes/1/revisions/100/approve"))
                .andExpect(status().isUnauthorized());
        then(commandService).should(never()).approve(anyLong(), anyLong(), anyLong());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 내_목록을_페이지로_조회한다() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        given(queryService.findMine(7L, pageable)).willReturn(new PageImpl<>(List.of(new CreatorQuizViews.Summary(
                1L, QuizFixtures.TITLE, QuizPublicationStatus.NOT_PUBLISHED, null, 100L, 1,
                QuizReviewStatus.PENDING_REVIEW, null, QuizFixtures.NOW, QuizFixtures.NOW)), pageable, 1));

        mockMvc.perform(get("/api/creator/quizzes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].quizId").value(1))
                .andExpect(jsonPath("$.data.items[0].latestReviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void revision_상세는_정답과_해설을_포함한다() throws Exception {
        given(queryService.findRevision(7L, 1L, 100L)).willReturn(new CreatorQuizViews.Revision(
                1L, 100L, 1, true, false, QuizFixtures.TITLE, QuizFixtures.VIDEO_ID, QuizFixtures.PROMPT_VERSION,
                QuizFixtures.CONTENT_TEXT, QuizReviewStatus.PENDING_REVIEW, null, null, QuizFixtures.NOW,
                List.of(new QuizQuestionContent(0, "질문", List.of("구름", "돌"), 1, "해설", "근거"))));

        mockMvc.perform(get("/api/creator/quizzes/1/revisions/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].correctOptionIndex").value(1))
                .andExpect(jsonPath("$.data.questions[0].options[1].optionNo").value(1))
                .andExpect(jsonPath("$.data.questions[0].options[1].text").value("돌"))
                .andExpect(jsonPath("$.data.questions[0].explanation").value("해설"))
                .andExpect(jsonPath("$.data.contentText").value(QuizFixtures.CONTENT_TEXT));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void Judge_평가가_없으면_승인과_게시는_409다() throws Exception {
        given(commandService.approve(7L, 1L, 100L))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED));
        given(commandService.publish(7L, 1L, 100L))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED));

        mockMvc.perform(post("/api/creator/quizzes/1/revisions/100/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUIZ_JUDGE_EVALUATION_REQUIRED"));
        mockMvc.perform(post("/api/creator/quizzes/1/publish")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revisionId\": 100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUIZ_JUDGE_EVALUATION_REQUIRED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 거절은_본문_없이도_요청할_수_있고_사유를_전달한다() throws Exception {
        QuizDecisionView rejected = new QuizDecisionView(1L, 100L, QuizReviewStatus.REJECTED, null, QuizFixtures.NOW);
        given(commandService.reject(7L, 1L, 100L, null)).willReturn(rejected);
        given(commandService.reject(7L, 1L, 100L, "오답")).willReturn(
                new QuizDecisionView(1L, 100L, QuizReviewStatus.REJECTED, "오답", QuizFixtures.NOW));

        mockMvc.perform(post("/api/creator/quizzes/1/revisions/100/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("REJECTED"));
        mockMvc.perform(post("/api/creator/quizzes/1/revisions/100/reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"오답\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("오답"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 거절_사유가_500자를_넘으면_400이다() throws Exception {
        mockMvc.perform(post("/api/creator/quizzes/1/revisions/100/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(commandService).should(never()).reject(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 게시는_revisionId가_필요하다() throws Exception {
        mockMvc.perform(post("/api/creator/quizzes/1/publish").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(commandService).should(never()).publish(anyLong(), anyLong(), anyLong());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 게시_취소는_공개_상태를_반환한다() throws Exception {
        given(commandService.unpublish(7L, 1L)).willReturn(new QuizPublicationView(
                1L, QuizPublicationStatus.UNPUBLISHED, null, QuizFixtures.NOW, QuizFixtures.NOW));

        mockMvc.perform(post("/api/creator/quizzes/1/unpublish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicationStatus").value("UNPUBLISHED"))
                .andExpect(jsonPath("$.data.publishedRevisionId").doesNotExist());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 다른_Creator의_Quiz는_403이고_없는_Quiz는_404다() throws Exception {
        given(queryService.findDetail(7L, 1L)).willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));
        given(queryService.findDetail(7L, 2L)).willThrow(new BusinessException(QuizErrorCode.QUIZ_NOT_FOUND));

        mockMvc.perform(get("/api/creator/quizzes/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/creator/quizzes/2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_NOT_FOUND"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 목록_size는_100을_넘을_수_없다() throws Exception {
        mockMvc.perform(get("/api/creator/quizzes").param("size", "101"))
                .andExpect(status().isBadRequest());
        then(queryService).should(never()).findMine(anyLong(), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 이미_거절한_revision의_승인은_409다() throws Exception {
        given(commandService.approve(7L, 1L, 100L))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_REVISION_ALREADY_DECIDED));

        mockMvc.perform(post("/api/creator/quizzes/1/revisions/100/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUIZ_REVISION_ALREADY_DECIDED"));
    }
}
