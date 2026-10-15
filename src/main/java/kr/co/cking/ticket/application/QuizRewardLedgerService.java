package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import kr.co.cking.ticket.domain.TicketEarnRequest;
import kr.co.cking.ticket.domain.TicketEarnRequestStatus;
import kr.co.cking.ticket.domain.TicketLedger;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.TicketEarnRequestRepository;
import kr.co.cking.ticket.repository.TicketLedgerRepository;
import kr.co.cking.ticket.repository.TicketQuizRewardRequestRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Quiz 보상 EARN 메시지를 Ticket 원장·DB 잔액에 반영한다(이슈 #533). 호출자(Stream 소비·Dead replay)는 이 메서드가
 * 예외 없이 끝난 뒤에만 XACK한다.
 *
 * <p>한 Transaction에서 전역 EARN request → Quiz 요청 → Balance 순서로 잠근 뒤 아래를 한다. 어느 단계에서든 예외가
 * 나면 원장·잔액·상태 변경이 모두 롤백된다. Redis는 호출하지 않는다.
 * <ol>
 *   <li>메시지의 requestId·attemptId·memberId·creatorId·정답 수·fingerprint를 저장된 요청과 대조하고, 저장된 요청으로
 *       fingerprint를 다시 계산한다. 지급량은 메시지 값이 아니라 저장된 요청의 값이다</li>
 *   <li>같은 requestId의 원장이 이미 있으면 Quiz 원인·회원·Creator·금액이 모두 맞고 요청이 APPLIED일 때만 정상 재전달로
 *       보고 다시 반영하지 않는다. 하나라도 다르면 예외다</li>
 *   <li>원장이 없으면 실행권 정책({@link QuizRewardExecutionPolicy})을 통과해야 한다. APPLIED인데 원장이 없으면
 *       정합성이 깨진 것이라 예외다</li>
 *   <li>Balance를 잠그거나 0으로 만든 뒤 Quiz EARN 원장을 남기고 잔액을 늘린다</li>
 *   <li>Quiz 요청을 APPLIED로, 전역 EARN request를 ACCEPTED로 바꾼다</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizRewardLedgerService {

    private final TicketEarnRequestRepository earnRequestRepository;
    private final TicketQuizRewardRequestRepository quizRewardRequestRepository;
    private final TicketLedgerRepository ticketLedgerRepository;
    private final UserTicketBalanceRepository userTicketBalanceRepository;
    private final QuizRewardExecutionPolicy executionPolicy;
    private final Clock clock;

    @Transactional
    public void apply(QuizEarnEvent event) {
        String requestId = event.requestId().toString();
        TicketEarnRequest claim = earnRequestRepository.findByRequestIdForUpdate(requestId)
                .orElseThrow(() -> mismatch("전역 EARN request가 없습니다", requestId));
        TicketQuizRewardRequest request = quizRewardRequestRepository.findByRewardRequestIdForUpdate(requestId)
                .orElseThrow(() -> mismatch("Quiz 보상 요청이 없습니다", requestId));
        verifySameRequest(claim, request, event);

        Optional<TicketLedger> existing = ticketLedgerRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            verifyApplied(existing.get(), request);
            log.info("이미 반영한 Quiz 보상 EARN이라 다시 반영하지 않습니다. requestId={}", requestId);
            return;
        }
        if (request.getStatus() == QuizRewardRequestStatus.APPLIED) {
            throw mismatch("APPLIED인데 원장이 없습니다", requestId);
        }
        executionPolicy.requireApplicable(request, event);

        Instant now = clock.instant();
        long amount = request.getAmount();
        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorIdForUpdate(request.getMemberId(), request.getCreatorId())
                .orElseGet(() -> userTicketBalanceRepository.save(UserTicketBalance.builder()
                        .memberId(request.getMemberId())
                        .creatorId(request.getCreatorId())
                        .balance(0L)
                        .updatedAt(now)
                        .build()));
        long balanceBefore = balance.getBalance();
        balance.applyDelta(amount, now);
        ticketLedgerRepository.save(TicketLedger.builder()
                .memberId(request.getMemberId())
                .creatorId(request.getCreatorId())
                .quizRewardRequestId(request.getQuizRewardRequestId())
                .deltaAmount(amount)
                .type(TicketLedgerType.EARN)
                .requestId(requestId)
                .balanceBefore(balanceBefore)
                .balanceAfter(balance.getBalance())
                .createdAt(now)
                .build());
        request.markApplied(now);
        if (claim.getStatus() == TicketEarnRequestStatus.PENDING) {
            claim.accept(now);
        }
    }

    /** 메시지·Quiz 요청·전역 EARN request가 모두 같은 요청인지 저장된 값으로 다시 계산해 확인한다. */
    private void verifySameRequest(TicketEarnRequest claim, TicketQuizRewardRequest request, QuizEarnEvent event) {
        String requestId = request.getRewardRequestId();
        String recomputed = new QuizRewardCommand(request.getAttemptId(), request.getMemberId(), request.getCreatorId(),
                request.getCorrectCount(), event.requestId()).computeFingerprint();
        boolean same = request.getAttemptId().equals(event.attemptId())
                && request.getMemberId().equals(event.memberId())
                && request.getCreatorId().equals(event.creatorId())
                && request.getCorrectCount() == event.correctCount()
                && request.getAmount() == event.amount()
                && request.hasSamePayload(event.fingerprintVersion(), event.fingerprint())
                && recomputed.equals(request.getPayloadFingerprint())
                && claim.hasSameRequest(event.fingerprint(), QuizRewardCommand.SOURCE);
        if (!same) {
            throw mismatch("메시지가 저장된 요청과 다릅니다", requestId);
        }
    }

    /** 기존 원장이 이 Quiz 요청의 반영 결과이고 요청도 APPLIED인지 확인한다. */
    private void verifyApplied(TicketLedger ledger, TicketQuizRewardRequest request) {
        boolean same = ledger.getType() == TicketLedgerType.EARN
                && ledger.getMissionCompletionId() == null
                && Objects.equals(ledger.getQuizRewardRequestId(), request.getQuizRewardRequestId())
                && ledger.getMemberId().equals(request.getMemberId())
                && ledger.getCreatorId().equals(request.getCreatorId())
                && ledger.getDeltaAmount().equals(request.getAmount())
                && request.getStatus() == QuizRewardRequestStatus.APPLIED;
        if (!same) {
            throw mismatch("기존 원장이 이 Quiz 보상 요청의 반영 결과와 다릅니다", request.getRewardRequestId());
        }
    }

    private static IllegalStateException mismatch(String reason, String requestId) {
        return new IllegalStateException("Quiz 보상 EARN을 반영할 수 없습니다: " + reason + ". requestId=" + requestId);
    }
}
