package kr.co.cking.quiz.application;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.QuizCompositionCommand;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuizCompositionStoreTest {
    final QuizCreatorLookup lookup=mock(QuizCreatorLookup.class);
    final QuizRepository quizzes=mock(QuizRepository.class);
    final QuizRevisionRepository revisions=mock(QuizRevisionRepository.class);
    final QuizQuestionRepository questions=mock(QuizQuestionRepository.class);
    final QuizOptionRepository options=mock(QuizOptionRepository.class);
    final QuizCompositionRequestRepository requests=mock(QuizCompositionRequestRepository.class);
    final QuizRevisionReader reader=mock(QuizRevisionReader.class);
    final Quiz quiz=QuizFixtures.quiz(1L,10L);
    final QuizRevision parent=QuizFixtures.revision(100L,1L,1,"a".repeat(64));
    final QuizQuestion original=new QuizQuestion(100L,0,"Q",1,"E","evidence");
    final List<String> choices=List.of("A","B","C","D");
    final QuizCompositionStore store=new QuizCompositionStore(lookup,quizzes,revisions,questions,options,requests,reader,
            Clock.fixed(QuizFixtures.NOW, ZoneOffset.UTC));
    @BeforeEach void setup() {
        var creator=mock(Creator.class); when(creator.getCreatorId()).thenReturn(10L); when(lookup.requireCreator(7L)).thenReturn(creator);
        when(quizzes.findByIdForUpdate(1L)).thenReturn(Optional.of(quiz));
        when(quizzes.findById(1L)).thenReturn(Optional.of(quiz));
        when(revisions.findById(100L)).thenReturn(Optional.of(parent));
        when(revisions.findTopByQuizIdOrderByRevisionNoDesc(1L)).thenReturn(Optional.of(parent));
        ReflectionTestUtils.setField(original,"questionId",1000L);
        when(questions.findById(1000L)).thenReturn(Optional.of(original));
        when(questions.findByRevisionIdOrderByQuestionNoAsc(100L)).thenReturn(List.of(original));
        when(reader.readVerified(quiz,parent)).thenReturn(List.of(new kr.co.cking.quiz.application.dto.QuizQuestionContent(0,"Q",choices,1,"E","evidence")));
        when(revisions.saveAndFlush(any())).thenAnswer(i->{QuizRevision r=i.getArgument(0);ReflectionTestUtils.setField(r,"revisionId",101L);return r;});
        when(questions.save(any())).thenAnswer(i->{QuizQuestion q=i.getArgument(0);ReflectionTestUtils.setField(q,"questionId",1010L+q.getQuestionNo());return q;});
    }
    QuizCompositionCommand command(QuizCompositionCommand.Selection... q){return new QuizCompositionCommand(UUID.randomUUID(),100L,"a".repeat(64),List.of(q));}
    @Test void 원본_선택과_수정_추가를_새_revision에_저장한다(){
        var cmd=command(new QuizCompositionCommand.Selection(1000L,null),new QuizCompositionCommand.Selection(null,
                new QuizCompositionCommand.Content("new",choices,0,"E",null)));
        var result=store.compose(7L,1L,cmd);
        assertThat(result.created()).isTrue();assertThat(result.revisionId()).isEqualTo(101L);
        var captor=org.mockito.ArgumentCaptor.forClass(QuizQuestion.class);verify(questions,times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(QuizQuestion::getQuestionNo).containsExactly(0,1);
        assertThat(captor.getAllValues()).extracting(QuizQuestion::getChangeType).containsExactly(QuizQuestionChangeType.AI_UNCHANGED,QuizQuestionChangeType.CREATOR_ADDED);
        assertThat(captor.getAllValues().get(1).getSourceEvidence()).isEmpty();verify(requests).saveAndFlush(any());
    }
    @Test void 수정은_근거를_상속하지_않는다(){
        store.compose(7L,1L,command(new QuizCompositionCommand.Selection(1000L,new QuizCompositionCommand.Content("changed",choices,2,"E",null))));
        var c=org.mockito.ArgumentCaptor.forClass(QuizQuestion.class);verify(questions).save(c.capture());
        assertThat(c.getValue().getChangeType()).isEqualTo(QuizQuestionChangeType.CREATOR_EDITED);
        assertThat(c.getValue().getOriginQuestionId()).isEqualTo(1000L);assertThat(c.getValue().getSourceEvidence()).isEmpty();
    }
    @Test void 게시후에도_성공요청은_replay한다(){
        var cmd=command(new QuizCompositionCommand.Selection(1000L,null));
        when(requests.findByQuizIdAndRequestId(1L,cmd.requestId().toString())).thenReturn(Optional.of(new QuizCompositionRequest(1L,cmd.requestId().toString(),cmd.fingerprint(),101L,7L,QuizFixtures.NOW)));
        var composed=QuizRevision.composed(parent,2,parent.getTitle(),1,4,"b".repeat(64),7L,QuizFixtures.NOW);
        ReflectionTestUtils.setField(composed,"revisionId",101L);when(revisions.findById(101L)).thenReturn(Optional.of(composed));
        quiz.publish(101L,"b".repeat(64),QuizFixtures.NOW);
        assertThat(store.compose(7L,1L,cmd).created()).isFalse();verify(revisions,never()).saveAndFlush(any());
    }
    @Test void stale_다른문항_게시잠금을_거부한다(){
        assertThatThrownBy(()->store.compose(7L,1L,command(new QuizCompositionCommand.Selection(999L,null)))).isInstanceOf(BusinessException.class);
        when(revisions.findTopByQuizIdOrderByRevisionNoDesc(1L)).thenReturn(Optional.of(QuizFixtures.revision(102L,1L,2)));
        assertThatThrownBy(()->store.compose(7L,1L,command(new QuizCompositionCommand.Selection(1000L,null)))).isInstanceOf(BusinessException.class);
        quiz.publish(100L,parent.getRevisionSha256(),QuizFixtures.NOW);quiz.unpublish(QuizFixtures.NOW);
        assertThatThrownBy(()->store.compose(7L,1L,command(new QuizCompositionCommand.Selection(1000L,null)))).isInstanceOf(BusinessException.class);
    }
    @Test void 같은_UUID의_다른_payload와_타인_Quiz를_거부한다(){
        var cmd=command(new QuizCompositionCommand.Selection(1000L,null));
        when(requests.findByQuizIdAndRequestId(1L,cmd.requestId().toString())).thenReturn(Optional.of(new QuizCompositionRequest(1L,cmd.requestId().toString(),"c".repeat(64),101L,7L,QuizFixtures.NOW)));
        assertThatThrownBy(()->store.compose(7L,1L,cmd)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_EDIT_REQUEST_CONFLICT));
        ReflectionTestUtils.setField(quiz,"creatorId",99L);
        assertThatThrownBy(()->store.compose(7L,1L,cmd)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(kr.co.cking.common.exception.CommonErrorCode.FORBIDDEN));
        verify(revisions,never()).saveAndFlush(any());
    }
}
