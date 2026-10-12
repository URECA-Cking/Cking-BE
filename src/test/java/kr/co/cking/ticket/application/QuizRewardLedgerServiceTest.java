package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import kr.co.cking.ticket.domain.TicketEarnRequest;
import kr.co.cking.ticket.domain.TicketEarnRequestStatus;
import kr.co.cking.ticket.domain.TicketLedger;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.TicketQuizRewardExecution;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.TicketEarnRequestRepository;
import kr.co.cking.ticket.repository.TicketLedgerRepository;
import kr.co.cking.ticket.repository.TicketQuizRewardExecutionRepository;
import kr.co.cking.ticket.repository.TicketQuizRewardRequestRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class QuizRewardLedgerServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T07:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final long QUIZ_REQUEST_ID = 5L;

    private final TicketEarnRequestRepository earnRequestRepository = mock(TicketEarnRequestRepository.class);
    private final TicketQuizRewardRequestRepository quizRepository = mock(TicketQuizRewardRequestRepository.class);
    private final TicketLedgerRepository ledgerRepository = mock(TicketLedgerRepository.class);
    private final UserTicketBalanceRepository balanceRepository = mock(UserTicketBalanceRepository.class);
    private final TicketQuizRewardExecutionRepository executionRepository =
            mock(TicketQuizRewardExecutionRepository.class);
    /** 운영 정책을 그대로 쓰고, 저장된 실행 기록만 mock으로 준다. */
    private final QuizRewardExecutionPolicy executionPolicy =
            new StoredExecutionQuizRewardExecutionPolicy(executionRepository);
    private final QuizRewardLedgerService service = new QuizRewardLedgerService(earnRequestRepository,
            quizRepository, ledgerRepository, balanceRepository, executionPolicy, Clock.fixed(NOW, ZoneOffset.UTC));

    private final QuizRewardCommand command = new QuizRewardCommand(100L, 7L, 10L, 3, REQUEST_ID);
    private final QuizEarnEvent event = new QuizEarnEvent(REQUEST_ID, 100L, 7L, 10L, 3, 3L,
            QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), "token-1");
    private TicketEarnRequest claim;
    private TicketQuizRewardRequest request;
    private UserTicketBalance balance;

    @BeforeEach
    void setUp() {
        claim = claim(command.computeFingerprint(), "QUIZ");
        request = request(QuizRewardRequestStatus.EXECUTING);
        balance = UserTicketBalance.builder().memberId(7L).creatorId(10L).balance(4L).updatedAt(NOW).build();
        given(earnRequestRepository.findByRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.of(claim));
        given(quizRepository.findByRewardRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.of(request));
        given(balanceRepository.findByMemberIdAndCreatorIdForUpdate(7L, 10L)).willReturn(Optional.of(balance));
        given(executionRepository.findByExecutionToken("token-1")).willReturn(Optional.of(
                TicketQuizRewardExecution.start(QUIZ_REQUEST_ID, 1, "token-1", NOW)));
    }

    @Test
    void 잠근_순서대로_검증한_뒤_원장_잔액_APPLIED_ACCEPTED를_한_Transaction에서_반영한다() throws Exception {
        service.apply(event);

        InOrder order = inOrder(earnRequestRepository, quizRepository, ledgerRepository, executionRepository,
                balanceRepository);
        order.verify(earnRequestRepository).findByRequestIdForUpdate(REQUEST_ID.toString());
        order.verify(quizRepository).findByRewardRequestIdForUpdate(REQUEST_ID.toString());
        order.verify(ledgerRepository).findByRequestId(REQUEST_ID.toString());
        order.verify(executionRepository).findByExecutionToken("token-1");
        order.verify(balanceRepository).findByMemberIdAndCreatorIdForUpdate(7L, 10L);
        order.verify(ledgerRepository).save(any(TicketLedger.class));
        TicketLedger ledger = savedLedger();
        assertThat(ledger.getType()).isEqualTo(TicketLedgerType.EARN);
        assertThat(ledger.getQuizRewardRequestId()).isEqualTo(QUIZ_REQUEST_ID);
        assertThat(ledger.getMissionCompletionId()).isNull();
        assertThat(ledger.getEventEntryId()).isNull();
        assertThat(ledger.getRequestId()).isEqualTo(REQUEST_ID.toString());
        assertThat(ledger.getDeltaAmount()).isEqualTo(3L);
        assertThat(ledger.getBalanceBefore()).isEqualTo(4L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(7L);
        assertThat(balance.getBalance()).isEqualTo(7L);
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.APPLIED);
        assertThat(request.getAppliedAt()).isEqualTo(NOW);
        verify(claim).accept(NOW);
        assertThat(QuizRewardLedgerService.class.getMethod("apply", QuizEarnEvent.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void Balance가_없으면_0으로_만든_뒤_늘린다() {
        given(balanceRepository.findByMemberIdAndCreatorIdForUpdate(7L, 10L)).willReturn(Optional.empty());
        given(balanceRepository.save(any(UserTicketBalance.class))).willAnswer(invocation -> invocation.getArgument(0));

        service.apply(event);

        ArgumentCaptor<UserTicketBalance> created = ArgumentCaptor.forClass(UserTicketBalance.class);
        verify(balanceRepository).save(created.capture());
        assertThat(created.getValue().getBalance()).isEqualTo(3L);
        assertThat(savedLedger().getBalanceBefore()).isZero();
    }

    @Test
    void 같은_요청의_원장이_있고_APPLIED면_다시_반영하지_않는다() {
        request.markApplied(NOW);
        given(ledgerRepository.findByRequestId(REQUEST_ID.toString())).willReturn(Optional.of(quizLedger(3L)));

        service.apply(event);

        verify(ledgerRepository, never()).save(any());
        verify(balanceRepository, never()).findByMemberIdAndCreatorIdForUpdate(any(), any());
        assertThat(balance.getBalance()).isEqualTo(4L);
    }

    @Test
    void 기존_원장이_있어도_원인_금액_상태가_다르면_정상_중복으로_보지_않는다() {
        request.markApplied(NOW);
        TicketLedger missionLedger = TicketLedger.builder().memberId(7L).creatorId(10L).missionCompletionId(1L)
                .deltaAmount(3L).type(TicketLedgerType.EARN).requestId(REQUEST_ID.toString()).build();
        for (TicketLedger ledger : new TicketLedger[] {missionLedger, quizLedger(2L)}) {
            given(ledgerRepository.findByRequestId(REQUEST_ID.toString())).willReturn(Optional.of(ledger));
            assertThatThrownBy(() -> service.apply(event)).isInstanceOf(IllegalStateException.class);
        }

        TicketQuizRewardRequest notApplied = request(QuizRewardRequestStatus.EXECUTING);
        given(quizRepository.findByRewardRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.of(notApplied));
        given(ledgerRepository.findByRequestId(REQUEST_ID.toString())).willReturn(Optional.of(quizLedger(3L)));
        assertThatThrownBy(() -> service.apply(event)).isInstanceOf(IllegalStateException.class);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void APPLIED는_되돌리거나_다시_적용하지_않는다() {
        request.markApplied(NOW);

        assertThatThrownBy(() -> service.apply(event)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.markApplied(NOW.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.APPLIED);
        assertThat(request.getAppliedAt()).isEqualTo(NOW);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void 메시지가_저장된_요청과_다르면_반영하지_않는다() {
        QuizRewardCommand other = new QuizRewardCommand(101L, 7L, 10L, 3, REQUEST_ID);
        QuizEarnEvent otherAttempt = new QuizEarnEvent(REQUEST_ID, 101L, 7L, 10L, 3, 3L,
                QuizRewardCommand.FINGERPRINT_VERSION, other.computeFingerprint(), "token-1");

        assertThatThrownBy(() -> service.apply(otherAttempt)).isInstanceOf(IllegalStateException.class);

        TicketEarnRequest missionClaim = claim(command.computeFingerprint(), "DAILY");
        given(earnRequestRepository.findByRequestIdForUpdate(REQUEST_ID.toString()))
                .willReturn(Optional.of(missionClaim));
        assertThatThrownBy(() -> service.apply(event)).isInstanceOf(IllegalStateException.class);

        ReflectionTestUtils.setField(request, "payloadFingerprint", "f".repeat(64));
        given(earnRequestRepository.findByRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.of(claim));
        assertThatThrownBy(() -> service.apply(event)).isInstanceOf(IllegalStateException.class);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void 전역_EARN_request나_Quiz_요청이_없으면_반영하지_않는다() {
        given(earnRequestRepository.findByRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.apply(event)).isInstanceOf(IllegalStateException.class);

        given(earnRequestRepository.findByRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.of(claim));
        given(quizRepository.findByRewardRequestIdForUpdate(REQUEST_ID.toString())).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.apply(event)).isInstanceOf(IllegalStateException.class);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void 실행권_정책이_막는_상태는_반영하지_않는다() {
        for (QuizRewardRequestStatus status : new QuizRewardRequestStatus[] {QuizRewardRequestStatus.RECEIVED,
                QuizRewardRequestStatus.RETRYABLE_FAILURE}) {
            given(quizRepository.findByRewardRequestIdForUpdate(REQUEST_ID.toString()))
                    .willReturn(Optional.of(request(status)));

            assertThatThrownBy(() -> service.apply(event)).isInstanceOf(IllegalStateException.class);
        }
        verify(ledgerRepository, never()).save(any());
        assertThat(balance.getBalance()).isEqualTo(4L);
    }

    @Test
    void 저장되지_않았거나_다른_요청의_실행권이면_허용_상태라도_원장_잔액_상태를_바꾸지_않는다() {
        QuizEarnEvent forged = new QuizEarnEvent(REQUEST_ID, 100L, 7L, 10L, 3, 3L,
                QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), "arbitrary-token");
        given(executionRepository.findByExecutionToken("other-request-token")).willReturn(Optional.of(
                TicketQuizRewardExecution.start(QUIZ_REQUEST_ID + 1, 1, "other-request-token", NOW)));
        QuizEarnEvent otherRequestToken = new QuizEarnEvent(REQUEST_ID, 100L, 7L, 10L, 3, 3L,
                QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), "other-request-token");

        for (QuizRewardRequestStatus status : new QuizRewardRequestStatus[] {QuizRewardRequestStatus.EXECUTING,
                QuizRewardRequestStatus.ACCEPTED, QuizRewardRequestStatus.UNKNOWN}) {
            for (QuizEarnEvent rejected : new QuizEarnEvent[] {forged, otherRequestToken}) {
                TicketQuizRewardRequest stored = request(status);
                given(quizRepository.findByRewardRequestIdForUpdate(REQUEST_ID.toString()))
                        .willReturn(Optional.of(stored));

                assertThatThrownBy(() -> service.apply(rejected)).isInstanceOf(IllegalStateException.class);
                assertThat(stored.getStatus()).isEqualTo(status);
                assertThat(stored.getAppliedAt()).isNull();
            }
        }
        verify(ledgerRepository, never()).save(any());
        verify(balanceRepository, never()).findByMemberIdAndCreatorIdForUpdate(any(), any());
        verify(balanceRepository, never()).save(any());
        assertThat(balance.getBalance()).isEqualTo(4L);
        verify(claim, never()).accept(any());
    }

    @Test
    void 이미_반영된_요청의_검증된_중복_전달은_실행권을_다시_보지_않고_멱등_처리한다() {
        request.markApplied(NOW);
        given(ledgerRepository.findByRequestId(REQUEST_ID.toString())).willReturn(Optional.of(quizLedger(3L)));

        service.apply(event);

        verify(ledgerRepository, never()).save(any());
        verify(executionRepository, never()).findByExecutionToken(any());
        assertThat(balance.getBalance()).isEqualTo(4L);
    }

    @Test
    void 원장_저장이_실패하면_예외를_올려_잔액과_상태_변경을_함께_롤백하게_한다() {
        given(ledgerRepository.save(any(TicketLedger.class))).willThrow(new IllegalStateException("db"));

        assertThatThrownBy(() -> service.apply(event)).isInstanceOf(IllegalStateException.class);
        // 상태 변경은 원장 저장 뒤라 실행되지 않고, 이미 바뀐 잔액은 Transaction 롤백으로 되돌아간다.
        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.EXECUTING);
        verify(claim, never()).accept(any());
    }

    private TicketLedger savedLedger() {
        ArgumentCaptor<TicketLedger> ledger = ArgumentCaptor.forClass(TicketLedger.class);
        verify(ledgerRepository).save(ledger.capture());
        return ledger.getValue();
    }

    private static TicketLedger quizLedger(long amount) {
        return TicketLedger.builder().memberId(7L).creatorId(10L).quizRewardRequestId(QUIZ_REQUEST_ID)
                .deltaAmount(amount).type(TicketLedgerType.EARN).requestId(REQUEST_ID.toString()).build();
    }

    private TicketQuizRewardRequest request(QuizRewardRequestStatus status) {
        TicketQuizRewardRequest stored = new TicketQuizRewardRequest(REQUEST_ID.toString(), 100L, 7L, 10L, 3,
                QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), NOW);
        ReflectionTestUtils.setField(stored, "quizRewardRequestId", QUIZ_REQUEST_ID);
        ReflectionTestUtils.setField(stored, "status", status);
        return stored;
    }

    private static TicketEarnRequest claim(String fingerprint, String rewardPolicy) {
        TicketEarnRequest claim = mock(TicketEarnRequest.class);
        given(claim.hasSameRequest(fingerprint, "QUIZ")).willReturn("QUIZ".equals(rewardPolicy));
        given(claim.getStatus()).willReturn(TicketEarnRequestStatus.PENDING);
        return claim;
    }
}
