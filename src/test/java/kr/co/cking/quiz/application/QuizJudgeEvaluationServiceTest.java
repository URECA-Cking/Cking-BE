package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.application.config.QuizJudgeEvaluationProperties;
import kr.co.cking.quiz.application.dto.QuizJudgeEvaluationView;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizJudgeFailure;
import kr.co.cking.quiz.domain.QuizJudgeJob;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import kr.co.cking.quiz.domain.QuizJudgeStoredResult;
import kr.co.cking.quiz.judge.JudgeAggregation;
import kr.co.cking.quiz.judge.QuizJudge;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class QuizJudgeEvaluationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static final long MEMBER_ID = 7L;
    private static final long CREATOR_ID = 10L;
    private static final long QUIZ_ID = 50L;
    private static final long REVISION_ID = 500L;
    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final QuizJudgeEvaluationStore store = mock(QuizJudgeEvaluationStore.class);
    private final QuizJudgeDispatcher dispatcher = mock(QuizJudgeDispatcher.class);
    private final QuizJudgeResultReader resultReader = mock(QuizJudgeResultReader.class);
    private final QuizJudge judge = mock(QuizJudge.class);
    private final QuizJudgeEvaluationProperties properties = new QuizJudgeEvaluationProperties();
    private QuizJudgeEvaluationService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        service = new QuizJudgeEvaluationService(new QuizCreatorLookup(memberRepository, creatorRepository), store,
                dispatcher, resultReader, judge, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator(CREATOR_ID)));
        given(judge.isConfigured()).willReturn(true);
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.empty());
        given(store.findResult(anyLong())).willReturn(Optional.empty());
    }

    @Test
    void 새_요청은_오래된_Job을_정리한_뒤_접수하고_Provider를_호출하지_않는다() {
        QuizJudgeJob created = job(1L, QuizJudgeJobStatus.PENDING, 1);
        given(store.create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW))
                .willReturn(new QuizJudgeEvaluationStore.Admission(created, true));

        QuizJudgeEvaluationView view = service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID);

        InOrder order = inOrder(store);
        order.verify(store).findByRequest(CREATOR_ID, REQUEST_ID.toString());
        order.verify(store).expireStale(CREATOR_ID, NOW);
        order.verify(store).create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW);
        assertThat(view.evaluationId()).isEqualTo(1L);
        assertThat(view.status()).isEqualTo(QuizJudgeJobStatus.PENDING);
        assertThat(view.attempt()).isEqualTo(1);
        assertThat(view.maxAttempts()).isEqualTo(2);
        assertThat(view.resultId()).isNull();
        verify(judge, never()).judge(any());
    }

    @Test
    void 새_Job만_접수_뒤_제출하고_제출_거절로_바뀐_상태를_다시_읽어_돌려준다() {
        QuizJudgeJob created = job(1L, QuizJudgeJobStatus.PENDING, 1);
        QuizJudgeJob rejected = job(1L, QuizJudgeJobStatus.FAILED, 1);
        ReflectionTestUtils.setField(rejected, "failureCode", QuizJudgeFailure.DISPATCH_REJECTED);
        given(store.create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW))
                .willReturn(new QuizJudgeEvaluationStore.Admission(created, true));
        given(store.findJob(1L)).willReturn(Optional.of(rejected));

        QuizJudgeEvaluationView view = service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID);

        InOrder order = inOrder(store, dispatcher);
        order.verify(store).create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW);
        order.verify(dispatcher).dispatch(1L);
        order.verify(store).findJob(1L);
        assertThat(view.status()).isEqualTo(QuizJudgeJobStatus.FAILED);
        assertThat(view.failureCode()).isEqualTo(QuizJudgeFailure.DISPATCH_REJECTED);
    }

    @Test
    void 처음_조회_뒤_같은_requestId가_먼저_접수됐으면_Store가_돌려준_기존_Job을_재요청으로_다룬다() {
        QuizJudgeJob concurrent = job(3L, QuizJudgeJobStatus.PENDING, 1);
        QuizJudgeJob latest = job(3L, QuizJudgeJobStatus.FAILED, 1);
        ReflectionTestUtils.setField(latest, "failureCode", QuizJudgeFailure.STALE_PENDING);
        given(store.create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW))
                .willReturn(new QuizJudgeEvaluationStore.Admission(concurrent, false));
        given(store.findJob(3L)).willReturn(Optional.of(latest));

        QuizJudgeEvaluationView view = service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID);

        assertThat(view.evaluationId()).isEqualTo(3L);
        assertThat(view.status()).isEqualTo(QuizJudgeJobStatus.FAILED);
        InOrder order = inOrder(store);
        order.verify(store).create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW);
        order.verify(store).expireStale(CREATOR_ID, NOW);
        order.verify(store).findJob(3L);
        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void 같은_requestId_같은_대상_재요청은_오래된_Job을_정리한_뒤_다시_읽은_상태를_돌려준다() {
        QuizJudgeJob stale = job(1L, QuizJudgeJobStatus.RUNNING, 1);
        QuizJudgeJob expired = job(1L, QuizJudgeJobStatus.UNKNOWN, 1);
        ReflectionTestUtils.setField(expired, "failureCode", QuizJudgeFailure.STALE_RUNNING);
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.of(stale));
        given(store.findJob(1L)).willReturn(Optional.of(expired));

        QuizJudgeEvaluationView view = service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID);

        InOrder order = inOrder(store);
        order.verify(store).expireStale(CREATOR_ID, NOW);
        order.verify(store).findJob(1L);
        assertThat(view.status()).isEqualTo(QuizJudgeJobStatus.UNKNOWN);
        assertThat(view.failureCode()).isEqualTo(QuizJudgeFailure.STALE_RUNNING);
        verify(store, never()).create(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void 기능이_꺼져_있거나_Provider_설정이_없어도_재요청과_조회는_된다() {
        properties.setEnabled(false);
        given(judge.isConfigured()).willReturn(false);
        QuizJudgeJob succeeded = job(1L, QuizJudgeJobStatus.SUCCEEDED, 1);
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.of(succeeded));
        given(store.findJob(1L)).willReturn(Optional.of(succeeded));
        QuizJudgeStoredResult result = mock(QuizJudgeStoredResult.class);
        given(result.getResultId()).willReturn(801L);
        given(result.getJobId()).willReturn(1L);
        given(result.getRevisionId()).willReturn(REVISION_ID);
        given(result.getAggregation()).willReturn(JudgeAggregation.HAS_FAIL);
        given(store.findResult(1L)).willReturn(Optional.of(result));
        given(resultReader.read(result)).willReturn(new QuizJudgeResultReader.Verified(result,
                new QuizApprovalPolicy.Target(QUIZ_ID, REVISION_ID, "a", "b"),
                new kr.co.cking.quiz.judge.QuizJudgeEvaluationValidator().validate(
                        new kr.co.cking.quiz.judge.QuizJudgeResponseParser(new tools.jackson.databind.ObjectMapper())
                                .parse(kr.co.cking.quiz.judge.QuizJudgeFixtures.allPass(3)), 3, 200)));

        QuizJudgeEvaluationView replayed = service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID);
        QuizJudgeEvaluationView found = service.find(MEMBER_ID, QUIZ_ID, REVISION_ID, 1L);

        for (QuizJudgeEvaluationView view : new QuizJudgeEvaluationView[] {replayed, found}) {
            assertThat(view.evaluationId()).isEqualTo(1L);
            assertThat(view.resultId()).isEqualTo(801L);
            assertThat(view.aggregation()).isEqualTo(JudgeAggregation.HAS_FAIL);
        }
        verify(store, never()).create(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
        // 재요청과 조회는 지금 실행 설정을 보지 않으므로 설정이 바뀌어도 된다.
        verify(judge, never()).currentSettings(anyInt());
    }

    @Test
    void 같은_requestId로_다른_Quiz나_revision을_요청하면_409이고_아무것도_바꾸지_않는다() {
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString()))
                .willReturn(Optional.of(job(1L, QuizJudgeJobStatus.PENDING, 1)));

        assertError(() -> service.request(MEMBER_ID, QUIZ_ID, 501L, REQUEST_ID),
                QuizErrorCode.QUIZ_JUDGE_REQUEST_CONFLICT);
        assertError(() -> service.request(MEMBER_ID, 51L, REVISION_ID, REQUEST_ID),
                QuizErrorCode.QUIZ_JUDGE_REQUEST_CONFLICT);
        verify(store, never()).expireStale(anyLong(), any());
        verify(store, never()).create(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void 기능이_꺼져_있거나_Provider_설정이_없으면_새_Job을_만들기_전에_503이다() {
        properties.setEnabled(false);
        assertError(() -> service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID),
                QuizErrorCode.QUIZ_JUDGE_DISABLED);

        properties.setEnabled(true);
        given(judge.isConfigured()).willReturn(false);
        assertError(() -> service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID),
                QuizErrorCode.QUIZ_JUDGE_NOT_CONFIGURED);

        verify(store, never()).expireStale(anyLong(), any());
        verify(store, never()).create(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void 고유_제약_충돌_뒤_같은_requestId가_보이면_재요청으로_처리하고_아니면_진행_중_409다() {
        given(store.create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW))
                .willThrow(new DataIntegrityViolationException("duplicate"));
        QuizJudgeJob concurrent = job(3L, QuizJudgeJobStatus.PENDING, 1);
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString()))
                .willReturn(Optional.empty(), Optional.of(concurrent));
        given(store.findJob(3L)).willReturn(Optional.of(concurrent));

        assertThat(service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID).evaluationId()).isEqualTo(3L);

        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.empty());
        assertError(() -> service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID),
                QuizErrorCode.QUIZ_JUDGE_IN_PROGRESS);
    }

    @Test
    void 잠금_실패_후_같은_requestId가_보이면_재제출하지_않는다() {
        QuizJudgeJob concurrent = job(3L, QuizJudgeJobStatus.PENDING, 1);
        given(store.create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW))
                .willThrow(new CannotAcquireLockException("deadlock"));
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString()))
                .willReturn(Optional.empty(), Optional.of(concurrent));
        given(store.findJob(3L)).willReturn(Optional.of(concurrent));

        assertThat(service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID).evaluationId()).isEqualTo(3L);
        InOrder order = inOrder(store);
        order.verify(store).create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW);
        order.verify(store).findByRequest(CREATOR_ID, REQUEST_ID.toString());
        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void 잠금_실패_후_새_접수가_성공하면_한_번만_제출한다() {
        QuizJudgeJob created = job(1L, QuizJudgeJobStatus.PENDING, 1);
        given(store.create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW))
                .willThrow(new CannotAcquireLockException("deadlock"))
                .willReturn(new QuizJudgeEvaluationStore.Admission(created, true));
        given(store.findJob(1L)).willReturn(Optional.of(created));

        assertThat(service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID).attempt()).isEqualTo(1);
        verify(store, times(2)).create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW);
        verify(dispatcher, times(1)).dispatch(1L);
        verify(judge, never()).judge(any());
    }

    @Test
    void 잠금_실패_후_경쟁_접수가_있으면_기존_409를_유지한다() {
        given(store.create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW))
                .willThrow(new CannotAcquireLockException("deadlock"))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_JUDGE_IN_PROGRESS));

        assertError(() -> service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID),
                QuizErrorCode.QUIZ_JUDGE_IN_PROGRESS);
        verify(store, times(2)).create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW);
        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void 잠금_실패_후_다른_대상의_requestId가_보이면_충돌이다() {
        QuizJudgeJob other = job(3L, QuizJudgeJobStatus.PENDING, 1);
        ReflectionTestUtils.setField(other, "revisionId", 501L);
        given(store.create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW))
                .willThrow(new CannotAcquireLockException("deadlock"));
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString()))
                .willReturn(Optional.empty(), Optional.of(other));

        assertError(() -> service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID),
                QuizErrorCode.QUIZ_JUDGE_REQUEST_CONFLICT);
        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void 잠금_실패는_세_번까지만_접수하고_진행_중이라고_추정하지_않는다() {
        given(store.create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW))
                .willThrow(new CannotAcquireLockException("deadlock"));

        assertError(() -> service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID), CommonErrorCode.SYSTEM_ERROR);
        verify(store, times(3)).create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW);
        verify(store, times(4)).findByRequest(CREATOR_ID, REQUEST_ID.toString());
        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void 잠금_실패_후_Store가_기존_접수를_돌려주면_재제출하지_않는다() {
        QuizJudgeJob concurrent = job(3L, QuizJudgeJobStatus.PENDING, 1);
        given(store.create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW))
                .willThrow(new CannotAcquireLockException("deadlock"))
                .willReturn(new QuizJudgeEvaluationStore.Admission(concurrent, false));
        given(store.findJob(3L)).willReturn(Optional.of(concurrent));

        assertThat(service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID).attempt()).isEqualTo(1);
        verify(store, times(2)).create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW);
        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void 접수_단계의_업무_오류는_그대로_전달한다() {
        for (QuizErrorCode code : new QuizErrorCode[] {QuizErrorCode.QUIZ_JUDGE_ATTEMPT_LIMIT_REACHED,
                QuizErrorCode.QUIZ_JUDGE_EVALUATION_COMPLETED, QuizErrorCode.QUIZ_JUDGE_REVISION_APPROVED,
                QuizErrorCode.QUIZ_JUDGE_IN_PROGRESS, QuizErrorCode.QUIZ_JUDGE_SETTINGS_MISMATCH,
                QuizErrorCode.QUIZ_JUDGE_REQUEST_CONFLICT, QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED}) {
            willThrow(new BusinessException(code)).given(store)
                    .create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID.toString(), NOW);
            assertError(() -> service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID), code);
        }
    }

    @Test
    void requestId가_없으면_400이다() {
        assertError(() -> service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, null), CommonErrorCode.VALIDATION_FAILED);
        verify(store, never()).findByRequest(anyLong(), anyString());
    }

    @Test
    void 조회는_경로에_속한_본인_Job만_되고_조회하면서_오래된_Job을_정리한다() {
        QuizJudgeJob own = job(1L, QuizJudgeJobStatus.PENDING, 1);
        given(store.findJob(1L)).willReturn(Optional.of(own));
        assertThat(service.find(MEMBER_ID, QUIZ_ID, REVISION_ID, 1L).evaluationId()).isEqualTo(1L);
        verify(store).expireStale(CREATOR_ID, NOW);

        clearInvocations(store);
        assertError(() -> service.find(MEMBER_ID, QUIZ_ID, 501L, 1L), QuizErrorCode.QUIZ_JUDGE_EVALUATION_NOT_FOUND);
        assertError(() -> service.find(MEMBER_ID, 51L, REVISION_ID, 1L), QuizErrorCode.QUIZ_JUDGE_EVALUATION_NOT_FOUND);
        given(store.findJob(2L)).willReturn(Optional.empty());
        assertError(() -> service.find(MEMBER_ID, QUIZ_ID, REVISION_ID, 2L),
                QuizErrorCode.QUIZ_JUDGE_EVALUATION_NOT_FOUND);
        QuizJudgeJob other = job(4L, QuizJudgeJobStatus.PENDING, 1);
        ReflectionTestUtils.setField(other, "creatorId", 99L);
        given(store.findJob(4L)).willReturn(Optional.of(other));
        assertError(() -> service.find(MEMBER_ID, QUIZ_ID, REVISION_ID, 4L), CommonErrorCode.FORBIDDEN);
        verify(store, never()).expireStale(anyLong(), any());
    }

    static QuizJudgeJob job(Long jobId, QuizJudgeJobStatus status, int attemptNo) {
        QuizJudgeJob job = (QuizJudgeJob) instantiate();
        ReflectionTestUtils.setField(job, "jobId", jobId);
        ReflectionTestUtils.setField(job, "seriesId", 30L);
        ReflectionTestUtils.setField(job, "quizId", QUIZ_ID);
        ReflectionTestUtils.setField(job, "revisionId", REVISION_ID);
        ReflectionTestUtils.setField(job, "creatorId", CREATOR_ID);
        ReflectionTestUtils.setField(job, "requestedByMemberId", MEMBER_ID);
        ReflectionTestUtils.setField(job, "requestId", REQUEST_ID.toString());
        ReflectionTestUtils.setField(job, "attemptNo", attemptNo);
        ReflectionTestUtils.setField(job, "status", status);
        ReflectionTestUtils.setField(job, "requestedAt", NOW);
        return job;
    }

    @Test
    void Creator_자격이_없으면_접수와_조회_모두_403이다() {
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        assertError(() -> service.request(MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID), CommonErrorCode.FORBIDDEN);
        assertError(() -> service.find(MEMBER_ID, QUIZ_ID, REVISION_ID, 1L), CommonErrorCode.FORBIDDEN);
        verify(store, never()).create(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void Job_조회_직후_완료_commit이_일어나면_상태를_다시_읽어_결과를_반환한다() {
        QuizJudgeJob pending = job(1L, QuizJudgeJobStatus.RUNNING, 1);
        QuizJudgeJob succeeded = job(1L, QuizJudgeJobStatus.SUCCEEDED, 1);
        given(store.findJob(1L)).willReturn(Optional.of(pending), Optional.of(pending), Optional.of(succeeded));
        QuizJudgeStoredResult result = mock(QuizJudgeStoredResult.class);
        given(result.getJobId()).willReturn(1L);
        given(result.getRevisionId()).willReturn(REVISION_ID);
        given(result.getResultId()).willReturn(801L);
        given(result.getAggregation()).willReturn(JudgeAggregation.ALL_PASS);
        given(store.findResult(1L)).willReturn(Optional.of(result));
        var evaluation = new kr.co.cking.quiz.judge.QuizJudgeEvaluationValidator().validate(
                new kr.co.cking.quiz.judge.QuizJudgeResponseParser(new tools.jackson.databind.ObjectMapper())
                        .parse(kr.co.cking.quiz.judge.QuizJudgeFixtures.allPass(3)), 3, 200);
        given(resultReader.read(result)).willReturn(new QuizJudgeResultReader.Verified(result,
                new QuizApprovalPolicy.Target(QUIZ_ID, REVISION_ID, "a", "b"), evaluation));

        QuizJudgeEvaluationView view = service.find(MEMBER_ID, QUIZ_ID, REVISION_ID, 1L);

        assertThat(view.status()).isEqualTo(QuizJudgeJobStatus.SUCCEEDED);
        assertThat(view.questions()).hasSize(3);
        verify(dispatcher, never()).dispatch(anyLong());
    }

    private static Object instantiate() {
        try {
            var constructor = QuizJudgeJob.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Creator creator(Long creatorId) {
        Creator creator = new Creator(MEMBER_ID, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }

    private static void assertError(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
