package kr.co.cking.quiz.presentation;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.application.*;
import kr.co.cking.quiz.application.dto.*;
import kr.co.cking.quiz.domain.QuizReviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Spring 기반이므로 로컬 실행 금지. CI에서 인증·HTTP 계약을 확인한다. */
@WebMvcTest(CreatorQuizCompositionController.class)
class CreatorQuizCompositionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CreatorQuizCompositionService compositions;
    @MockitoBean CreatorQuizCommandService commands;
    @MockitoBean CreatorQuizQueryService queries;
    @MockitoBean QuizCompositionMetadataReader metadata;
    @MockitoBean MemberRepository members;
    String payload="{\"requestId\":\"750e8400-e29b-41d4-a716-446655440000\",\"baseRevisionId\":100,\"baseRevisionSha256\":\""+"a".repeat(64)+"\",\"questions\":[{\"sourceQuestionId\":1000}]}";
    @Test void 미인증은_permitAll_fallback에_빠지지_않는다() throws Exception {
        mvc.perform(post("/api/creator/quizzes/1/revisions").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/creator/quizzes/1/revisions/101/creator-approval").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(compositions,commands);
    }
    @Test @WithMockJwt(memberId="7") void 신규201_replay200을_구분한다() throws Exception {
        when(compositions.compose(eq(7L),eq(1L),any())).thenReturn(new QuizCompositionStore.Admission(101L,true),new QuizCompositionStore.Admission(101L,false));
        when(queries.findRevision(7L,1L,101L)).thenReturn(new CreatorQuizViews.Revision(1L,101L,2,true,false,"T","dQw4w9WgXcQ","v1","text",QuizReviewStatus.PENDING_REVIEW,null,null,null,List.of()));
        mvc.perform(post("/api/creator/quizzes/1/revisions").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.revisionId").value(101));
        mvc.perform(post("/api/creator/quizzes/1/revisions").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
    }
    @Test @WithMockJwt(memberId="7") void 잘못된_문항은_400이다() throws Exception {
        mvc.perform(post("/api/creator/quizzes/1/revisions").contentType(MediaType.APPLICATION_JSON).content(payload.replace("[{\"sourceQuestionId\":1000}]","[]")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(compositions);
    }
    @Test @WithMockJwt(memberId="7") void 책임승인_200과_오류코드_매핑을_유지한다() throws Exception {
        when(commands.approveByCreator(7L,1L,101L,"b".repeat(64),true)).thenReturn(new QuizDecisionView(1L,101L,QuizReviewStatus.APPROVED,null,java.time.Instant.EPOCH));
        mvc.perform(post("/api/creator/quizzes/1/revisions/101/creator-approval").contentType(MediaType.APPLICATION_JSON).content("{\"revisionSha256\":\""+"b".repeat(64)+"\",\"acknowledgeResponsibility\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"));
        when(compositions.compose(eq(7L),eq(1L),any())).thenThrow(new kr.co.cking.common.exception.BusinessException(kr.co.cking.common.exception.CommonErrorCode.FORBIDDEN));
        mvc.perform(post("/api/creator/quizzes/1/revisions").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
    }
}
