package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.QuizRewardExecutionStore.FinishOutcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnResult;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardExecutionStatus;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import kr.co.cking.ticket.domain.TicketEarnRequest;
import kr.co.cking.ticket.domain.TicketQuizRewardExecution;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import kr.co.cking.ticket.repository.TicketEarnRequestRepository;
import kr.co.cking.ticket.repository.TicketQuizRewardExecutionRepository;
import kr.co.cking.ticket.repository.TicketQuizRewardRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/** 실행 결과 기록({@link QuizRewardExecutionStore#finish})의 상태 전이와 거부 조건. */
class QuizRewardExecutionFinishTest {

    private static final Instant NOW = Instant.parse("2026-10-12T06:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Long QUIZ_REQUEST_ID = 1L;
    private static final String TOKEN_1 = "00000000-0000-0000-0000-000000000001";
    private static final String TOKEN_2 = "00000000-0000-0000-0000-000000000002";

    private final TicketEarnRequestRepository earnRequestRepository = mock(TicketEarnRequestRepository.class);
    private final TicketQuizRewardRequestRepository quizRepository = mock(TicketQuizRewardRequestRepository.class);
    private final TicketQuizRewardExecutionRepository executionRepository =
            mock(TicketQuizRewardExecutionRepository.class);
    private final QuizRewardExecutionStore store = new QuizRewardExecutionStore(earnRequestRepository, quizRepository,
            executionRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    private final QuizRewardCommand command = new QuizRewardCommand(100L, 7L, 10L, 3, REQUEST_ID);
    private final List<TicketQuizRewardExecution> executions = new ArrayList<>();
    private TicketQuizRewardRequest request;

    @BeforeEach
    void setUp() {
        given(earnRequestRepository.findByRequestIdForUpdate(REQUEST_ID.toString()))
                .willReturn(Optional.of(mock(TicketEarnRequest.class)));
        request = setRequest(QuizRewardRequestStatus.EXECUTING);
        given(executionRepository.findByExecutionToken(anyString())).willAnswer(invocation -> executions.stream()
                .filter(execution -> execution.getExecutionToken().equals(invocation.getArgument(0))).findFirst());
        given(executionRepository.findFirstByQuizRewardRequestIdOrderByExecutionSeqDesc(QUIZ_REQUEST_ID))
                .willAnswer(invocation -> executions.stream()
                        .filter(execution -> execution.getQuizRewardRequestId().equals(QUIZ_REQUEST_ID))
                        .max(Comparator.comparingInt(TicketQuizRewardExecution::getExecutionSeq)));
    }

    @Test
    void 결과_기록은_독립_Transaction_하나다() throws NoSuchMethodException {
        assertThat(QuizRewardExecutionStore.class.getMethod("finish", UUID.class, String.class,
                QuizRedisEarnResult.class).getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void ACCEPTED면_전역_request_Quiz_요청_실행_순서로_잠그고_Stream_ID를_남기며_요청을_ACCEPTED로_바꾼다() {
        TicketQuizRewardExecution current = started(1, TOKEN_1);

        FinishOutcome outcome = store.finish(REQUEST_ID, TOKEN_1, accepted("1-0", null));

        InOrder order = inOrder(earnRequestRepository, quizRepository, executionRepository);
        order.verify(earnRequestRepository).findByRequestIdForUpdate(REQUEST_ID.toString());
        order.verify(quizRepository).findByRewardRequestIdForUpdate(REQUEST_ID.toString());
        order.verify(executionRepository).findByExecutionToken(TOKEN_1);
        assertThat(outcome).isEqualTo(FinishOutcome.RECORDED);
        assertFinished(current, "ACCEPTED", null, "1-0", false);
        // Redis 수락은 원장 반영(APPLIED)이 아니다.
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.ACCEPTED);
        assertThat(request.getAppliedAt()).isNull();
    }

    @Test
    void guard_완료_기록_실패_사유도_남긴다() {
        TicketQuizRewardExecution current = started(1, TOKEN_1);

        store.finish(REQUEST_ID, TOKEN_1, accepted("1-0", QuizRedisEarnResult.GUARD_NOT_COMPLETED));

        assertFinished(current, "ACCEPTED", "GUARD_NOT_COMPLETED", "1-0", false);
    }

    @Test
    void 무반영이_확인되고_다시_실행해도_되는_결과만_RETRYABLE_FAILURE다() {
        for (QuizRedisEarnResult result : List.of(
                QuizRedisEarnResult.of(QuizRedisEarnOutcome.RETRYABLE_FAILURE, "XADD_FAILED"),
                QuizRedisEarnResult.of(QuizRedisEarnOutcome.BALANCE_MAINTENANCE, null),
                QuizRedisEarnResult.of(QuizRedisEarnOutcome.BALANCE_NOT_LOADED, "BALANCE_LOAD_FAILED"))) {
            reset(QuizRewardRequestStatus.EXECUTING);
            TicketQuizRewardExecution current = started(1, TOKEN_1);

            assertThat(store.finish(REQUEST_ID, TOKEN_1, result)).isEqualTo(FinishOutcome.RECORDED);

            assertFinished(current, result.outcome().name(), result.detail(), null, true);
            assertThat(request.getStatus()).as(result.outcome().name())
                    .isEqualTo(QuizRewardRequestStatus.RETRYABLE_FAILURE);
        }
    }

    @Test
    void UNKNOWN은_무반영으로_기록하지_않고_요청을_UNKNOWN으로_바꾼다() {
        TicketQuizRewardExecution current = started(1, TOKEN_1);

        store.finish(REQUEST_ID, TOKEN_1, QuizRedisEarnResult.unknown("TIMEOUT"));

        assertFinished(current, "UNKNOWN", "TIMEOUT", null, false);
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.UNKNOWN);
    }

    @Test
    void 충돌과_입력_오류는_무반영이지만_다시_실행하지_않도록_UNKNOWN으로_둔다() {
        for (QuizRedisEarnResult result : List.of(
                QuizRedisEarnResult.of(QuizRedisEarnOutcome.REQUEST_ID_CONFLICT, null),
                QuizRedisEarnResult.of(QuizRedisEarnOutcome.INVALID_REQUEST, "balanceKey"))) {
            reset(QuizRewardRequestStatus.EXECUTING);
            TicketQuizRewardExecution current = started(1, TOKEN_1);

            store.finish(REQUEST_ID, TOKEN_1, result);

            assertFinished(current, result.outcome().name(), result.detail(), null, true);
            assertThat(request.getStatus()).as(result.outcome().name()).isEqualTo(QuizRewardRequestStatus.UNKNOWN);
        }
    }

    @Test
    void ALREADY_ACCEPTED는_guard의_실행권이_이_요청의_이전_적립_실행일_때만_ACCEPTED다() {
        executions.add(finished(1, TOKEN_1, "ACCEPTED", false));
        TicketQuizRewardExecution current = started(2, TOKEN_2);

        store.finish(REQUEST_ID, TOKEN_2, alreadyAccepted(TOKEN_1));

        assertFinished(current, "ALREADY_ACCEPTED", null, "1-0", false);
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.ACCEPTED);
    }

    @Test
    void ALREADY_ACCEPTED의_실행권을_확인할_수_없으면_UNKNOWN으로_남기고_재적립하지_않는다() {
        // 모르는 실행권
        TicketQuizRewardExecution current = started(1, TOKEN_1);
        store.finish(REQUEST_ID, TOKEN_1, alreadyAccepted("00000000-0000-0000-0000-00000000000f"));
        assertFinished(current, "UNKNOWN", "ALREADY_ACCEPTED_TOKEN_UNVERIFIED", null, false);
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.UNKNOWN);

        // 무반영으로 기록된 이전 실행의 실행권
        reset(QuizRewardRequestStatus.EXECUTING);
        executions.add(finished(1, TOKEN_1, "RETRYABLE_FAILURE", true));
        current = started(2, TOKEN_2);
        store.finish(REQUEST_ID, TOKEN_2, alreadyAccepted(TOKEN_1));
        assertFinished(current, "UNKNOWN", "ALREADY_ACCEPTED_TOKEN_UNVERIFIED", null, false);
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.UNKNOWN);

        // 다른 요청의 실행권
        reset(QuizRewardRequestStatus.EXECUTING);
        TicketQuizRewardExecution foreign = TicketQuizRewardExecution.start(2L, 1, TOKEN_2, NOW);
        executions.add(foreign);
        current = started(1, TOKEN_1);
        store.finish(REQUEST_ID, TOKEN_1, alreadyAccepted(TOKEN_2));
        assertThat(current.getOutcome()).isEqualTo("UNKNOWN");
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.UNKNOWN);
    }

    @Test
    void ALREADY_ACCEPTED는_실행_기록의_Stream_ID가_있으면_같을_때만_ACCEPTED다() {
        // 같은 실행권, 같은 Stream ID
        executions.add(finished(1, TOKEN_1, "ACCEPTED", false));
        TicketQuizRewardExecution current = started(2, TOKEN_2);
        store.finish(REQUEST_ID, TOKEN_2, alreadyAccepted(TOKEN_1, "1-0"));
        assertFinished(current, "ALREADY_ACCEPTED", null, "1-0", false);
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.ACCEPTED);

        // 같은 실행권, 다른 Stream ID
        reset(QuizRewardRequestStatus.EXECUTING);
        executions.add(finished(1, TOKEN_1, "ACCEPTED", false));
        current = started(2, TOKEN_2);
        store.finish(REQUEST_ID, TOKEN_2, alreadyAccepted(TOKEN_1, "2-0"));
        assertFinished(current, "UNKNOWN", "ALREADY_ACCEPTED_STREAM_MISMATCH", null, false);
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.UNKNOWN);
    }

    @Test
    void 실행_기록에_Stream_ID가_없으면_실행권_확인만으로_판단한다() {
        // 결과 없이 남은 실행과 UNKNOWN으로 끝난 실행은 Stream ID가 없다. 무반영으로 확인되지 않았으므로 적립 근거다.
        for (boolean finishedAsUnknown : List.of(false, true)) {
            reset(QuizRewardRequestStatus.EXECUTING);
            TicketQuizRewardExecution previous = finishedAsUnknown
                    ? finished(1, TOKEN_1, "UNKNOWN", false)
                    : TicketQuizRewardExecution.start(QUIZ_REQUEST_ID, 1, TOKEN_1, NOW);
            ReflectionTestUtils.setField(previous, "quizRewardExecutionId", 99L);
            executions.add(previous);
            TicketQuizRewardExecution current = started(2, TOKEN_2);

            store.finish(REQUEST_ID, TOKEN_2, alreadyAccepted(TOKEN_1, "2-0"));

            assertFinished(current, "ALREADY_ACCEPTED", null, "2-0", false);
            assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.ACCEPTED);
        }
    }

    @Test
    void APPLIED가_먼저면_Stream_ID가_달라도_APPLIED를_유지한다() {
        reset(QuizRewardRequestStatus.APPLIED);
        ReflectionTestUtils.setField(request, "appliedAt", NOW);
        executions.add(finished(1, TOKEN_1, "ACCEPTED", false));
        TicketQuizRewardExecution current = started(2, TOKEN_2);

        FinishOutcome outcome = store.finish(REQUEST_ID, TOKEN_2, alreadyAccepted(TOKEN_1, "2-0"));

        assertThat(outcome).isEqualTo(FinishOutcome.RECORDED_REQUEST_ALREADY_APPLIED);
        assertFinished(current, "UNKNOWN", "ALREADY_ACCEPTED_STREAM_MISMATCH", null, false);
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.APPLIED);
    }

    @Test
    void 원장_반영이_먼저_끝났으면_실행_기록만_끝내고_APPLIED를_되돌리지_않는다() {
        for (QuizRedisEarnResult result : List.of(accepted("1-0", null), QuizRedisEarnResult.unknown("TIMEOUT"),
                QuizRedisEarnResult.of(QuizRedisEarnOutcome.RETRYABLE_FAILURE, "XADD_FAILED"))) {
            reset(QuizRewardRequestStatus.APPLIED);
            ReflectionTestUtils.setField(request, "appliedAt", NOW);
            TicketQuizRewardExecution current = started(1, TOKEN_1);

            FinishOutcome outcome = store.finish(REQUEST_ID, TOKEN_1, result);

            assertThat(outcome).isEqualTo(FinishOutcome.RECORDED_REQUEST_ALREADY_APPLIED);
            assertThat(current.getStatus()).isEqualTo(QuizRewardExecutionStatus.FINISHED);
            assertThat(request.getStatus()).as(result.outcome().name()).isEqualTo(QuizRewardRequestStatus.APPLIED);
            assertThat(request.getAppliedAt()).isEqualTo(NOW);
        }
    }

    @Test
    void 진행_중인_최근_실행의_실행권이_아니면_아무것도_바꾸지_않는다() {
        // 모르는 실행권
        TicketQuizRewardExecution current = started(1, TOKEN_1);
        assertRejected(TOKEN_2);
        assertThat(current.isStarted()).isTrue();

        // 이미 결과를 기록한 실행(늦게 도착한 같은 결과·이전 실행 결과)
        reset(QuizRewardRequestStatus.EXECUTING);
        TicketQuizRewardExecution previous = finished(1, TOKEN_1, "RETRYABLE_FAILURE", true);
        executions.add(previous);
        started(2, TOKEN_2);
        assertRejected(TOKEN_1);
        assertThat(previous.getOutcome()).isEqualTo("RETRYABLE_FAILURE");

        // 다른 요청의 실행권
        reset(QuizRewardRequestStatus.EXECUTING);
        executions.add(TicketQuizRewardExecution.start(2L, 1, TOKEN_2, NOW));
        started(1, TOKEN_1);
        assertRejected(TOKEN_2);
    }

    @Test
    void 실행_중이_아닌_요청에는_기록하지_않는다() {
        for (QuizRewardRequestStatus status : List.of(QuizRewardRequestStatus.RECEIVED,
                QuizRewardRequestStatus.ACCEPTED, QuizRewardRequestStatus.UNKNOWN,
                QuizRewardRequestStatus.RETRYABLE_FAILURE)) {
            reset(status);
            TicketQuizRewardExecution current = started(1, TOKEN_1);

            assertRejected(TOKEN_1);
            assertThat(current.isStarted()).isTrue();
            assertThat(request.getStatus()).isEqualTo(status);
        }
    }

    @Test
    void 요청이_없으면_기록하지_않는다() {
        started(1, TOKEN_1);
        given(quizRepository.findByRewardRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.empty());
        assertRejected(TOKEN_1);
        given(earnRequestRepository.findByRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.empty());
        assertRejected(TOKEN_1);
    }

    @Test
    void 사유는_안전한_코드만_남긴다() {
        assertThat(QuizRewardExecutionStore.safeReason(null)).isNull();
        assertThat(QuizRewardExecutionStore.safeReason("XADD_FAILED")).isEqualTo("XADD_FAILED");
        assertThat(QuizRewardExecutionStore.safeReason("balanceKey")).isEqualTo("balanceKey");
        assertThat(QuizRewardExecutionStore.safeReason("ERR wrong type")).isEqualTo("UNRECOGNIZED_REASON");
        assertThat(QuizRewardExecutionStore.safeReason("A".repeat(65))).isEqualTo("UNRECOGNIZED_REASON");
        assertThat(QuizRewardExecutionStore.safeReason("")).isEqualTo("UNRECOGNIZED_REASON");
    }

    private void assertRejected(String token) {
        QuizRewardRequestStatus before = request.getStatus();
        assertThat(store.finish(REQUEST_ID, token, accepted("1-0", null))).isEqualTo(FinishOutcome.REJECTED);
        assertThat(request.getStatus()).isEqualTo(before);
    }

    private void assertFinished(TicketQuizRewardExecution execution, String outcome, String reason, String streamId,
                                boolean notExecutedConfirmed) {
        assertThat(execution.getStatus()).isEqualTo(QuizRewardExecutionStatus.FINISHED);
        assertThat(execution.getOutcome()).isEqualTo(outcome);
        assertThat(execution.getReason()).isEqualTo(reason);
        assertThat(execution.getStreamId()).isEqualTo(streamId);
        assertThat(execution.isNotExecutedConfirmed()).isEqualTo(notExecutedConfirmed);
        assertThat(execution.getFinishedAt()).isEqualTo(NOW);
    }

    private void reset(QuizRewardRequestStatus status) {
        executions.clear();
        request = setRequest(status);
    }

    private TicketQuizRewardRequest setRequest(QuizRewardRequestStatus status) {
        TicketQuizRewardRequest stored = new TicketQuizRewardRequest(REQUEST_ID.toString(), 100L, 7L, 10L, 3,
                QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), NOW);
        ReflectionTestUtils.setField(stored, "quizRewardRequestId", QUIZ_REQUEST_ID);
        ReflectionTestUtils.setField(stored, "status", status);
        given(quizRepository.findByRewardRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.of(stored));
        return stored;
    }

    private TicketQuizRewardExecution started(int seq, String token) {
        TicketQuizRewardExecution execution = TicketQuizRewardExecution.start(QUIZ_REQUEST_ID, seq, token, NOW);
        ReflectionTestUtils.setField(execution, "quizRewardExecutionId", (long) seq * 10 + executions.size());
        executions.add(execution);
        return execution;
    }

    private TicketQuizRewardExecution finished(int seq, String token, String outcome, boolean notExecutedConfirmed) {
        TicketQuizRewardExecution execution = TicketQuizRewardExecution.start(QUIZ_REQUEST_ID, seq, token, NOW);
        ReflectionTestUtils.setField(execution, "quizRewardExecutionId", (long) seq * 10 + executions.size());
        String streamId = "ACCEPTED".equals(outcome) || "ALREADY_ACCEPTED".equals(outcome) ? "1-0" : null;
        execution.finish(outcome, null, streamId, notExecutedConfirmed, NOW);
        return execution;
    }

    private static QuizRedisEarnResult accepted(String streamId, String detail) {
        return new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, streamId, 5L, null, detail);
    }

    private static QuizRedisEarnResult alreadyAccepted(String storedToken) {
        return alreadyAccepted(storedToken, "1-0");
    }

    private static QuizRedisEarnResult alreadyAccepted(String storedToken, String streamId) {
        return new QuizRedisEarnResult(QuizRedisEarnOutcome.ALREADY_ACCEPTED, streamId, null, storedToken, null);
    }
}
