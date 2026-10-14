package kr.co.cking.quiz.application;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class QuizCompositionMetadataReaderTest {
    @Test void 원본으로_돌려도_수정이력은_미재검증이고_추가는_미평가다(){
        var quizzes=mock(QuizRepository.class);var revisions=mock(QuizRevisionRepository.class);var questions=mock(QuizQuestionRepository.class);
        var decisions=mock(QuizRevisionDecisionRepository.class);var results=mock(QuizJudgeStoredResultRepository.class);
        var judges=mock(QuizJudgeResultReader.class);var reader=mock(QuizRevisionReader.class);
        var quiz=QuizFixtures.quiz(1L,10L);var parent=QuizFixtures.revision(100L,1L,1);
        var revision=QuizRevision.composed(parent,2,"T",2,4,"b".repeat(64),7L,QuizFixtures.NOW);ReflectionTestUtils.setField(revision,"revisionId",101L);
        when(quizzes.findById(1L)).thenReturn(Optional.of(quiz));when(revisions.findById(101L)).thenReturn(Optional.of(revision));
        when(revisions.findTopByQuizIdOrderByRevisionNoDesc(1L)).thenReturn(Optional.of(revision));
        var origin=new QuizQuestion(100L,0,"Q",0,"E","S");ReflectionTestUtils.setField(origin,"questionId",1L);
        var edited=QuizQuestion.composed(100L,0,"changed",0,"E","",QuizQuestionChangeType.CREATOR_EDITED,1L,1L);ReflectionTestUtils.setField(edited,"questionId",2L);
        var restored=QuizQuestion.composed(101L,0,"Q",0,"E","S",QuizQuestionChangeType.AI_UNCHANGED,1L,2L);ReflectionTestUtils.setField(restored,"questionId",3L);
        var added=QuizQuestion.composed(101L,1,"new",0,"E","",QuizQuestionChangeType.CREATOR_ADDED,null,null);ReflectionTestUtils.setField(added,"questionId",4L);
        when(questions.findByRevisionIdOrderByQuestionNoAsc(101L)).thenReturn(List.of(restored,added));
        when(questions.findById(1L)).thenReturn(Optional.of(origin));when(questions.findById(2L)).thenReturn(Optional.of(edited));
        var service=new QuizCompositionMetadataReader(quizzes,revisions,questions,decisions,results,judges,reader);
        assertThat(service.read(1L,101L).questions()).extracting(q->q.evaluationStatus()).containsExactly("NOT_REVALIDATED","NOT_EVALUATED");
        verifyNoInteractions(judges);
    }
}
