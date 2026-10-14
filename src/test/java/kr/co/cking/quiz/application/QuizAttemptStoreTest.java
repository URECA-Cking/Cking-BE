package kr.co.cking.quiz.application;
import kr.co.cking.common.exception.*;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.*;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class QuizAttemptStoreTest {
    final MemberRepository members=mock(MemberRepository.class);
    final CreatorRepository creators=mock(CreatorRepository.class);
    final QuizRepository quizzes=mock(QuizRepository.class);
    final QuizRevisionRepository revisions=mock(QuizRevisionRepository.class);
    final QuizRevisionReader reader=mock(QuizRevisionReader.class);
    final QuizAttemptRepository attempts=mock(QuizAttemptRepository.class);
    final QuizAttemptAnswerRepository answers=mock(QuizAttemptAnswerRepository.class);
    final ObjectMapper mapper=new ObjectMapper();
    final QuizAttemptSnapshotReader snapshots=new QuizAttemptSnapshotReader(answers,mapper);
    final QuizAttemptStore store=new QuizAttemptStore(members,creators,quizzes,revisions,reader,attempts,answers,snapshots,mapper,Clock.fixed(QuizFixtures.NOW,ZoneOffset.UTC));
    final Quiz quiz=QuizFixtures.quiz(2L,1L);
    final QuizRevision revision=QuizFixtures.revision(100L,2L,1);
    QuizAttempt saved; List<QuizAttemptAnswer> savedAnswers=List.of();
    QuizAttemptCommand command(int a,int b) {return new QuizAttemptCommand(7L,1L,2L,UUID.fromString("750e8400-e29b-41d4-a716-446655440000"),100L,List.of(new QuizAttemptCommand.Answer(0,a),new QuizAttemptCommand.Answer(1,b)));}
    @BeforeEach void setup() {
        when(members.existsById(7L)).thenReturn(true);when(quizzes.findByIdForUpdate(2L)).thenReturn(Optional.of(quiz));
        when(creators.findById(1L)).thenReturn(Optional.of(new Creator(8L,"creator")));
        when(revisions.findById(100L)).thenReturn(Optional.of(revision));
        quiz.publish(100L,revision.getRevisionSha256(),QuizFixtures.NOW);
        when(reader.readVerified(quiz,revision)).thenReturn(QuizFixtures.questions().stream().map(q -> new QuizQuestionContent(QuizFixtures.questions().indexOf(q),q.question(),q.options(),q.correctOptionIndex(),q.explanation(),q.sourceEvidence())).toList());
        when(attempts.saveAndFlush(any())).thenAnswer(i->{saved=i.getArgument(0);ReflectionTestUtils.setField(saved,"attemptId",10L);return saved;});
        when(answers.saveAllAndFlush(any())).thenAnswer(i->{savedAnswers=i.getArgument(0);return savedAnswers;});
        when(answers.findByAttemptIdOrderByQuestionNoAsc(10L)).thenAnswer(i->savedAnswers);
    }
    /** 게시 고정 이전 데이터처럼 다른 revision이 게시돼 있는 상태. */
    private void publishedAs(Long revisionId) {
        ReflectionTestUtils.setField(quiz,"publicationStatus",kr.co.cking.quiz.domain.QuizPublicationStatus.PUBLISHED);
        ReflectionTestUtils.setField(quiz,"publishedRevisionId",revisionId);
    }
    private void error(Runnable action, QuizErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));
    }
    @Test void 서버_채점과_양수_보상_UUID를_저장한다() {
        var result=store.submit(command(0,1));assertThat(result.correctCount()).isEqualTo(2);assertThat(result.reward().amount()).isEqualTo(2);
        assertThat(result.reward().requestId()).isNotNull();assertThat(result.reward().status()).isEqualTo("NOT_INTEGRATED");
        assertThat(result.snapshotSha256()).hasSize(64);assertThat(result.questions().get(0).explanation()).isNotBlank();
    }
    @Test void 영점은_UUID없이_보상_대상에서_제외한다() {
        var result=store.submit(command(1,0));assertThat(result.correctCount()).isZero();assertThat(result.reward().requestId()).isNull();assertThat(result.reward().status()).isEqualTo("NOT_ELIGIBLE");
    }
    @Test void 게시_취소후_replay와_본인_GET은_스냅샷을_반환한다() {
        var command=command(0,1);var result=store.submit(command);quiz.unpublish(QuizFixtures.NOW);
        when(attempts.findByMemberIdAndRequestId(7L,command.requestId().toString())).thenReturn(Optional.of(saved));
        when(attempts.findByMemberIdAndQuizId(7L,2L)).thenReturn(Optional.of(saved));
        clearInvocations(reader);assertThat(store.findExisting(command)).contains(result);assertThat(store.findMine(7L,1L,2L)).isEqualTo(result);verifyNoInteractions(reader);
    }
    @Test void revision_교체후_기존_결과를_재채점하지_않는다() {
        var command=command(0,1);var result=store.submit(command);publishedAs(101L);
        when(attempts.findByMemberIdAndRequestId(7L,command.requestId().toString())).thenReturn(Optional.of(saved));
        assertThat(store.findExisting(command)).contains(result);
    }
    @Test void UUID_재사용과_다른_UUID_재응시를_구분한다() {
        var c=command(0,1);store.submit(c);when(attempts.findByMemberIdAndRequestId(7L,c.requestId().toString())).thenReturn(Optional.of(saved));
        error(()->store.findExisting(command(1,1)),QuizErrorCode.QUIZ_ATTEMPT_REQUEST_CONFLICT);
        when(attempts.findByMemberIdAndQuizId(7L,2L)).thenReturn(Optional.of(saved));
        error(()->store.findExisting(new QuizAttemptCommand(7L,1L,2L,UUID.randomUUID(),100L,c.answers())),QuizErrorCode.QUIZ_ALREADY_ATTEMPTED);
    }
    @Test void 게시_취소와_revision_불일치_신규_제출을_막는다() {
        quiz.unpublish(QuizFixtures.NOW);error(()->store.submit(command(0,1)),QuizErrorCode.QUIZ_NOT_FOUND);
        publishedAs(101L);error(()->store.submit(command(0,1)),QuizErrorCode.QUIZ_PUBLISHED_REVISION_CHANGED);verify(attempts,never()).saveAndFlush(any());
    }
    @Test void Creator_본인과_ADMIN_본인은_역할에_상관없이_거부한다() {
        when(creators.findById(1L)).thenReturn(Optional.of(new Creator(7L,"self")));
        assertThatThrownBy(()->store.submit(command(0,1))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
        verify(attempts,never()).saveAndFlush(any());
    }
    @Test void 문항_누락과_선택지_범위_초과를_거부한다() {
        assertThatThrownBy(()->store.submit(new QuizAttemptCommand(7L,1L,2L,UUID.randomUUID(),100L,List.of(new QuizAttemptCommand.Answer(0,0))))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->store.submit(command(2,1))).isInstanceOf(BusinessException.class);verify(attempts,never()).saveAndFlush(any());
    }
    @Test void snapshot_hash_점수_및_문항_누락을_차단한다() {
        store.submit(command(0,1));ReflectionTestUtils.setField(saved,"snapshotSha256","0".repeat(64));error(()->snapshots.read(saved),QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
    }
    @Test void 저장된_문항_일부가_없으면_차단한다() {
        store.submit(command(0,1));savedAnswers=savedAnswers.subList(0,1);error(()->snapshots.read(saved),QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
    }
    @Test void 본인_기록_없음과_다른_Creator_경로는_404다() {
        error(()->store.findMine(7L,1L,2L),QuizErrorCode.QUIZ_ATTEMPT_NOT_FOUND);
        store.submit(command(0,1));when(attempts.findByMemberIdAndQuizId(7L,2L)).thenReturn(Optional.of(saved));error(()->store.findMine(7L,99L,2L),QuizErrorCode.QUIZ_ATTEMPT_NOT_FOUND);
    }
    @Test void 다른_Member는_결과에_접근할_수_없다() {
        store.submit(command(0,1));when(members.existsById(8L)).thenReturn(true);
        error(()->store.findMine(8L,1L,2L),QuizErrorCode.QUIZ_ATTEMPT_NOT_FOUND);
    }
    @Test void 원본_revision_변조를_검증한_뒤_저장하지_않는다() {
        when(reader.readVerified(quiz,revision)).thenThrow(new BusinessException(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED));
        error(()->store.submit(command(0,1)),QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);verify(attempts,never()).saveAndFlush(any());
    }
    @Test void 저장_답안_보기_정답_점수_변조를_차단한다() {
        store.submit(command(0,1));ReflectionTestUtils.setField(savedAnswers.get(0),"explanation","변조");error(()->snapshots.read(saved),QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
        store.submit(command(0,1));ReflectionTestUtils.setField(saved,"correctCount",0);error(()->snapshots.read(saved),QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
        store.submit(command(0,1));ReflectionTestUtils.setField(savedAnswers.get(0),"optionsJson","[\"변조\",\"돌\"]");error(()->snapshots.read(saved),QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
    }
    @Test void 같은_UUID를_다른_Quiz_대상에_재사용하면_충돌이다() {
        var c=command(0,1);store.submit(c);when(attempts.findByMemberIdAndRequestId(7L,c.requestId().toString())).thenReturn(Optional.of(saved));
        error(()->store.findExisting(new QuizAttemptCommand(7L,1L,3L,c.requestId(),100L,c.answers())),QuizErrorCode.QUIZ_ATTEMPT_REQUEST_CONFLICT);
    }
}
