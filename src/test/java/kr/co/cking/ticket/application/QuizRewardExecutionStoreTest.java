package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.QuizRewardExecutionClaim.Outcome;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardExecutionStatus;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import kr.co.cking.ticket.domain.TicketEarnRequest;
import kr.co.cking.ticket.domain.TicketEarnRequestStatus;
import kr.co.cking.ticket.domain.TicketQuizRewardExecution;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import kr.co.cking.ticket.repository.TicketEarnRequestRepository;
import kr.co.cking.ticket.repository.TicketQuizRewardExecutionRepository;
import kr.co.cking.ticket.repository.TicketQuizRewardRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class QuizRewardExecutionStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-12T06:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Long QUIZ_REQUEST_ID = 1L;
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final TicketEarnRequestRepository earnRequestRepository = mock(TicketEarnRequestRepository.class);
    private final TicketQuizRewardRequestRepository quizRepository = mock(TicketQuizRewardRequestRepository.class);
    private final TicketQuizRewardExecutionRepository executionRepository =
            mock(TicketQuizRewardExecutionRepository.class);
    private final QuizRewardExecutionStore store = new QuizRewardExecutionStore(earnRequestRepository, quizRepository,
            executionRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    private final QuizRewardCommand command = new QuizRewardCommand(100L, 7L, 10L, 3, REQUEST_ID);
    private final List<TicketQuizRewardExecution> saved = new ArrayList<>();
    private TicketQuizRewardRequest request;

    @BeforeEach
    void setUp() {
        givenEarnRequest(earnRequest(true, TicketEarnRequestStatus.PENDING));
        request = request(QuizRewardRequestStatus.RECEIVED);
        given(quizRepository.findByRewardRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.of(request));
        given(executionRepository.findFirstByQuizRewardRequestIdOrderByExecutionSeqDesc(QUIZ_REQUEST_ID))
                .willAnswer(invocation -> saved.isEmpty() ? Optional.empty() : Optional.of(saved.get(saved.size() - 1)));
        given(executionRepository.saveAndFlush(any(TicketQuizRewardExecution.class))).willAnswer(invocation -> {
            TicketQuizRewardExecution execution = invocation.getArgument(0);
            saved.add(execution);
            return execution;
        });
    }

    @Test
    void 발급은_독립_Transaction_하나다() throws NoSuchMethodException {
        assertThat(QuizRewardExecutionStore.class.getMethod("claim", QuizRewardCommand.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void RECEIVED면_전역_request_Quiz_요청_최근_실행_순서로_잠근_뒤_순번_1_실행권을_발급하고_EXECUTING으로_바꾼다() {
        QuizRewardExecutionClaim claim = store.claim(command);

        InOrder order = inOrder(earnRequestRepository, quizRepository, executionRepository);
        order.verify(earnRequestRepository).findByRequestIdForUpdate(REQUEST_ID.toString());
        order.verify(quizRepository).findByRewardRequestIdForUpdate(REQUEST_ID.toString());
        order.verify(executionRepository).findFirstByQuizRewardRequestIdOrderByExecutionSeqDesc(QUIZ_REQUEST_ID);
        order.verify(executionRepository).saveAndFlush(any(TicketQuizRewardExecution.class));
        assertThat(claim.outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(claim.quizRewardRequestId()).isEqualTo(QUIZ_REQUEST_ID);
        assertThat(claim.executionSeq()).isEqualTo(1);
        assertThat(claim.executionToken()).matches(UUID_PATTERN);
        TicketQuizRewardExecution execution = saved.get(0);
        assertThat(execution.getQuizRewardRequestId()).isEqualTo(QUIZ_REQUEST_ID);
        assertThat(execution.getExecutionSeq()).isEqualTo(1);
        assertThat(execution.getExecutionToken()).isEqualTo(claim.executionToken());
        assertThat(execution.getStatus()).isEqualTo(QuizRewardExecutionStatus.STARTED);
        assertThat(execution.getStartedAt()).isEqualTo(NOW);
        assertThat(execution.getOutcome()).isNull();
        assertThat(execution.isNotExecutedConfirmed()).isFalse();
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.EXECUTING);
    }

    @Test
    void 이미_실행권을_발급했으면_다시_발급하지_않는다() {
        QuizRewardExecutionClaim first = store.claim(command);

        QuizRewardExecutionClaim second = store.claim(command);

        assertThat(first.outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(second).isEqualTo(QuizRewardExecutionClaim.rejected(Outcome.ALREADY_EXECUTING));
        assertThat(saved).hasSize(1);
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.EXECUTING);
    }

    @Test
    void ACCEPTED_UNKNOWN_APPLIED는_실행권을_발급하지_않고_아무것도_바꾸지_않는다() {
        assertRejected(QuizRewardRequestStatus.ACCEPTED, Outcome.ALREADY_ACCEPTED);
        assertRejected(QuizRewardRequestStatus.UNKNOWN, Outcome.UNKNOWN);
        givenEarnRequest(earnRequest(true, TicketEarnRequestStatus.ACCEPTED));
        assertRejected(QuizRewardRequestStatus.APPLIED, Outcome.ALREADY_APPLIED);
    }

    @Test
    void UNKNOWN은_이전_실행이_무반영으로_보이는_기록이_있어도_다시_실행하지_않는다() {
        saved.add(finished(1, "RETRYABLE_FAILURE", true));
        assertRejected(QuizRewardRequestStatus.UNKNOWN, Outcome.UNKNOWN);
    }

    @Test
    void APPLIED는_최종_상태라_어떤_실행_기록이_있어도_다시_실행하지_않는다() {
        givenEarnRequest(earnRequest(true, TicketEarnRequestStatus.ACCEPTED));
        saved.add(finished(1, "RETRYABLE_FAILURE", true));
        assertRejected(QuizRewardRequestStatus.APPLIED, Outcome.ALREADY_APPLIED);
    }

    @Test
    void 무반영이_확인된_재시도_가능한_실패면_순번을_늘린_새_실행권을_발급하고_이전_기록은_그대로_둔다() {
        request = setRequest(QuizRewardRequestStatus.RETRYABLE_FAILURE);
        TicketQuizRewardExecution previous = finished(1, "RETRYABLE_FAILURE", true);
        saved.add(previous);

        QuizRewardExecutionClaim claim = store.claim(command);

        assertThat(claim.outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(claim.executionSeq()).isEqualTo(2);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(1)).isNotSameAs(previous);
        assertThat(previous.getExecutionSeq()).isEqualTo(1);
        assertThat(previous.getStatus()).isEqualTo(QuizRewardExecutionStatus.FINISHED);
        assertThat(previous.getOutcome()).isEqualTo("RETRYABLE_FAILURE");
        assertThat(previous.getExecutionToken()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(claim.executionToken()).isNotEqualTo(previous.getExecutionToken());
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.EXECUTING);
        verify(executionRepository, never()).delete(any());
        verify(executionRepository, never()).deleteById(anyLong());
    }

    @Test
    void 보정_락이나_Balance_미적재로_무반영이_확인된_실행도_재시도할_수_있다() {
        for (String outcome : List.of("BALANCE_MAINTENANCE", "BALANCE_NOT_LOADED")) {
            saved.clear();
            request = setRequest(QuizRewardRequestStatus.RETRYABLE_FAILURE);
            saved.add(finished(1, outcome, true));

            assertThat(store.claim(command).outcome()).as(outcome).isEqualTo(Outcome.CLAIMED);
        }
    }

    @Test
    void 이전_실행의_무반영_증거가_없으면_RETRYABLE_FAILURE라도_다시_실행하지_않는다() {
        // 실행 기록 없음
        assertNotRetryable();
        // 결과 미기록
        saved.add(TicketQuizRewardExecution.start(QUIZ_REQUEST_ID, 1, "00000000-0000-0000-0000-000000000001", NOW));
        assertNotRetryable();
        // 결과는 있지만 무반영 미확인
        saved.clear();
        saved.add(finished(1, "RETRYABLE_FAILURE", false));
        assertNotRetryable();
        // 무반영이 확인됐어도 재시도 가능한 분류가 아님
        for (String outcome : List.of("REQUEST_ID_CONFLICT", "INVALID_REQUEST", "UNKNOWN", "ACCEPTED", "NOPE")) {
            saved.clear();
            saved.add(finished(1, outcome, true));
            assertNotRetryable();
        }
    }

    @Test
    void 실행권은_매번_새_UUID다() {
        request = setRequest(QuizRewardRequestStatus.RETRYABLE_FAILURE);
        List<String> tokens = new ArrayList<>();
        for (int seq = 1; seq <= 5; seq++) {
            saved.add(finished(seq, "RETRYABLE_FAILURE", true));
            tokens.add(store.claim(command).executionToken());
            saved.remove(saved.size() - 1);
            ReflectionTestUtils.setField(request, "status", QuizRewardRequestStatus.RETRYABLE_FAILURE);
        }

        assertThat(tokens).doesNotHaveDuplicates().allMatch(token -> token.matches(UUID_PATTERN));
    }

    @Test
    void 실행_기록_저장이_실패하면_예외를_올리고_요청_상태를_바꾸지_않는다() {
        given(executionRepository.saveAndFlush(any(TicketQuizRewardExecution.class)))
                .willThrow(new DataIntegrityViolationException("uk_ticket_quiz_reward_execution_started"));

        assertThatThrownBy(() -> store.claim(command)).isInstanceOf(DataIntegrityViolationException.class);
        // 요청 상태 변경은 실행 기록 저장 뒤라 실행되지 않는다. 실제 DB 변경은 Transaction 롤백으로 남지 않는다.
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.RECEIVED);
    }

    @Test
    void 입력이나_저장된_상태가_맞지_않으면_MISMATCH이고_아무것도_바꾸지_않는다() {
        // 전역 request 없음
        given(earnRequestRepository.findByRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.empty());
        assertMismatch(command);
        // 전역 request가 다른 원인·내용
        givenEarnRequest(earnRequest(false, TicketEarnRequestStatus.PENDING));
        assertMismatch(command);
        givenEarnRequest(earnRequest(true, TicketEarnRequestStatus.PENDING));
        // Quiz 요청 없음
        given(quizRepository.findByRewardRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.empty());
        assertMismatch(command);
        given(quizRepository.findByRewardRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.of(request));
        // 저장된 fingerprint가 다르다
        ReflectionTestUtils.setField(request, "payloadFingerprint", "f".repeat(64));
        assertMismatch(command);
        request = setRequest(QuizRewardRequestStatus.RECEIVED);
        // 저장된 입력이 다르다(fingerprint는 같게 둔다)
        ReflectionTestUtils.setField(request, "attemptId", 101L);
        assertMismatch(command);
        request = setRequest(QuizRewardRequestStatus.RECEIVED);
        // 전역 request만 ACCEPTED
        givenEarnRequest(earnRequest(true, TicketEarnRequestStatus.ACCEPTED));
        assertMismatch(command);
        givenEarnRequest(earnRequest(true, TicketEarnRequestStatus.PENDING));
        // RECEIVED인데 실행 이력이 있다
        saved.add(finished(1, "RETRYABLE_FAILURE", true));
        assertMismatch(command);
        // EXECUTING인데 진행 중인 실행이 없다
        request = setRequest(QuizRewardRequestStatus.EXECUTING);
        assertMismatch(command);
        verify(executionRepository, never()).saveAndFlush(any());
    }

    private void assertRejected(QuizRewardRequestStatus status, Outcome expected) {
        request = setRequest(status);
        QuizRewardExecutionClaim claim = store.claim(command);
        assertThat(claim).as(status.name()).isEqualTo(QuizRewardExecutionClaim.rejected(expected));
        assertThat(request.getStatus()).isEqualTo(status);
        verify(executionRepository, never()).saveAndFlush(any());
    }

    private void assertNotRetryable() {
        request = setRequest(QuizRewardRequestStatus.RETRYABLE_FAILURE);
        int before = saved.size();
        assertThat(store.claim(command)).isEqualTo(QuizRewardExecutionClaim.rejected(Outcome.NOT_RETRYABLE));
        assertThat(saved).hasSize(before);
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.RETRYABLE_FAILURE);
    }

    private void assertMismatch(QuizRewardCommand input) {
        QuizRewardRequestStatus before = request.getStatus();
        assertThat(store.claim(input)).isEqualTo(QuizRewardExecutionClaim.rejected(Outcome.MISMATCH));
        assertThat(request.getStatus()).isEqualTo(before);
    }

    private TicketQuizRewardRequest setRequest(QuizRewardRequestStatus status) {
        TicketQuizRewardRequest stored = request(status);
        given(quizRepository.findByRewardRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.of(stored));
        return stored;
    }

    private TicketQuizRewardRequest request(QuizRewardRequestStatus status) {
        TicketQuizRewardRequest stored = new TicketQuizRewardRequest(REQUEST_ID.toString(), 100L, 7L, 10L, 3,
                QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), NOW);
        ReflectionTestUtils.setField(stored, "quizRewardRequestId", QUIZ_REQUEST_ID);
        ReflectionTestUtils.setField(stored, "status", status);
        return stored;
    }

    private void givenEarnRequest(TicketEarnRequest earnRequest) {
        given(earnRequestRepository.findByRequestIdForUpdate(REQUEST_ID.toString()))
                .willReturn(Optional.of(earnRequest));
    }

    private TicketEarnRequest earnRequest(boolean sameQuizRequest, TicketEarnRequestStatus status) {
        TicketEarnRequest earnRequest = mock(TicketEarnRequest.class);
        given(earnRequest.hasSameRequest(command.computeFingerprint(), "QUIZ")).willReturn(sameQuizRequest);
        given(earnRequest.getStatus()).willReturn(status);
        return earnRequest;
    }

    private static TicketQuizRewardExecution finished(int seq, String outcome, boolean notExecutedConfirmed) {
        TicketQuizRewardExecution execution = TicketQuizRewardExecution.start(QUIZ_REQUEST_ID, seq,
                "00000000-0000-0000-0000-00000000000" + seq, NOW);
        ReflectionTestUtils.setField(execution, "status", QuizRewardExecutionStatus.FINISHED);
        ReflectionTestUtils.setField(execution, "outcome", outcome);
        ReflectionTestUtils.setField(execution, "notExecutedConfirmed", notExecutedConfirmed);
        ReflectionTestUtils.setField(execution, "finishedAt", NOW);
        return execution;
    }
}
