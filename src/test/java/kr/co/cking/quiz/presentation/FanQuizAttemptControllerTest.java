package kr.co.cking.quiz.presentation;
import kr.co.cking.common.exception.*;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.application.FanQuizAttemptService;
import kr.co.cking.quiz.application.dto.*;
import kr.co.cking.quiz.domain.QuizErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
@WebMvcTest(FanQuizAttemptController.class)
class FanQuizAttemptControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean FanQuizAttemptService service;
    @MockitoBean MemberRepository members;
    final String path="/api/creators/1/quizzes/2";
    final String body="{\"requestId\":\"750e8400-e29b-41d4-a716-446655440000\",\"revisionId\":100,\"answers\":[{\"questionNo\":0,\"selectedOptionIndex\":0}]}";
    private QuizAttemptResult result() {
        return new QuizAttemptResult(1L,UUID.fromString("750e8400-e29b-41d4-a716-446655440000"),1L,2L,100L,"제목","video",1,1,Instant.parse("2026-10-12T00:00:00Z"),"a".repeat(64),"b".repeat(64),List.of(new QuizAttemptResult.Question(0,"문제",List.of("보기1","보기2"),0,0,true,"해설","근거")),new QuizAttemptResult.Reward(1,UUID.randomUUID(),"NOT_INTEGRATED"));
    }
    @Test void 두_경로는_JWT가_필수다() throws Exception {
        mvc.perform(post(path+"/attempts").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(get(path+"/my-attempt")).andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test @WithMockJwt(memberId="7") void 신규와_replay는_200이며_정답을_본인에게_반환한다() throws Exception {
        when(service.submit(any())).thenReturn(result());
        for(int i=0;i<2;i++) mvc.perform(post(path+"/attempts").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.correctCount").value(1)).andExpect(jsonPath("$.data.questions[0].correctOptionIndex").value(0))
            .andExpect(jsonPath("$.data.questions[0].explanation").value("해설")).andExpect(jsonPath("$.data.questions[0].sourceEvidence").value("근거"));
        verify(service,times(2)).submit(argThat(c->c.memberId().equals(7L)&&c.creatorId().equals(1L)));
    }
    @Test @WithMockJwt(memberId="7") void 본인_GET은_인증_Member만_전달한다() throws Exception {
        when(service.findMine(7L,1L,2L)).thenReturn(result());
        mvc.perform(get(path+"/my-attempt")).andExpect(status().isOk()).andExpect(jsonPath("$.data.attemptId").value(1));
        verify(service).findMine(7L,1L,2L);
    }
    @Test @WithMockJwt(memberId="7") void 결과_없음은_404다() throws Exception {
        when(service.findMine(7L,1L,2L)).thenThrow(new BusinessException(QuizErrorCode.QUIZ_ATTEMPT_NOT_FOUND));
        mvc.perform(get(path+"/my-attempt")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUIZ_ATTEMPT_NOT_FOUND"));
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"requestId\":\"invalid\"}",
        "{\"requestId\":\"750e8400-e29b-41d4-a716-446655440000\",\"revisionId\":100,\"answers\":[{}]}",
        "{\"requestId\":\"750e8400-e29b-41d4-a716-446655440000\",\"revisionId\":100,\"answers\":[null]}",
        "{\"requestId\":\"750e8400-e29b-41d4-a716-446655440000\",\"revisionId\":100,\"answers\":[{\"questionNo\":0,\"selectedOptionIndex\":-1}]}"})
    @WithMockJwt(memberId="7") void 잘못된_요청은_400이다(String invalid) throws Exception {
        mvc.perform(post(path+"/attempts").contentType(MediaType.APPLICATION_JSON).content(invalid))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));verifyNoInteractions(service);
    }
    @Test @WithMockJwt(memberId="7") void 응시_충돌은_409다() throws Exception {
        for(var code:List.of(QuizErrorCode.QUIZ_ALREADY_ATTEMPTED,QuizErrorCode.QUIZ_ATTEMPT_REQUEST_CONFLICT,QuizErrorCode.QUIZ_PUBLISHED_REVISION_CHANGED)) {
            doThrow(new BusinessException(code)).when(service).submit(any());mvc.perform(post(path+"/attempts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code.name()));
        }
    }
    @Test @WithMockJwt(memberId="7") void ADMIN도_본인_Quiz_금지를_우회하지_못한다() throws Exception {
        when(service.submit(any())).thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));
        var context = org.springframework.security.test.context.TestSecurityContextHolder.getContext();
        var original = (org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) context.getAuthentication();
        context.setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(original.getToken(), List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        mvc.perform(post(path+"/attempts")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verify(service).submit(argThat(c->c.memberId().equals(7L)));
    }
}
