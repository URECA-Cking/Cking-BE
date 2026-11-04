package kr.co.cking.quiz.application;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.repository.*;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class CreatorQuizResponsibilityTest {
    @Test void 책임승인은_Judge없이_정확한_hash를_고정하고_게시한다() {
        var lookup=mock(QuizCreatorLookup.class);var quizzes=mock(QuizRepository.class);var revisions=mock(QuizRevisionRepository.class);
        var decisions=mock(QuizRevisionDecisionRepository.class);var reader=mock(QuizRevisionReader.class);var policy=mock(QuizApprovalPolicy.class);
        var creator=mock(Creator.class);when(creator.getCreatorId()).thenReturn(10L);when(lookup.requireCreator(7L)).thenReturn(creator);
        var quiz=QuizFixtures.quiz(1L,10L);when(quizzes.findByIdForUpdate(1L)).thenReturn(Optional.of(quiz));
        var parent=QuizFixtures.revision(100L,1L,1);var revision=QuizRevision.composed(parent,2,"title",1,4,"b".repeat(64),7L,QuizFixtures.NOW);
        ReflectionTestUtils.setField(revision,"revisionId",101L);when(revisions.findById(101L)).thenReturn(Optional.of(revision));
        when(revisions.findTopByQuizIdOrderByRevisionNoDesc(1L)).thenReturn(Optional.of(revision));
        when(decisions.save(any())).thenAnswer(i->i.getArgument(0));
        var service=new CreatorQuizCommandService(lookup,quizzes,revisions,decisions,reader,policy,Clock.fixed(QuizFixtures.NOW,ZoneOffset.UTC));
        assertThatThrownBy(()->service.approveByCreator(7L,1L,101L,"a".repeat(64),true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.approveByCreator(7L,1L,101L,"b".repeat(64),false)).isInstanceOf(BusinessException.class);
        service.approveByCreator(7L,1L,101L,"b".repeat(64),true);
        var c=org.mockito.ArgumentCaptor.forClass(QuizRevisionDecision.class);verify(decisions).save(c.capture());
        assertThat(c.getValue().getApprovalPolicy()).isEqualTo(QuizApprovalPolicyType.CREATOR_REVIEW_V1);
        assertThat(c.getValue().getJudgeEvaluationId()).isNull();
        when(decisions.findByRevisionId(101L)).thenReturn(Optional.of(c.getValue()));
        service.approveByCreator(7L,1L,101L,"b".repeat(64),true);
        service.publish(7L,1L,101L);assertThat(quiz.isContentLocked()).isTrue();
        verifyNoInteractions(policy);
        assertThatThrownBy(()->service.approve(7L,1L,101L)).isInstanceOf(BusinessException.class);
    }
}
