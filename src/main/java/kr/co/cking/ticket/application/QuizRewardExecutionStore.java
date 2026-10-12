package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.QuizRewardExecutionClaim.Outcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import kr.co.cking.ticket.domain.TicketEarnRequest;
import kr.co.cking.ticket.domain.TicketEarnRequestStatus;
import kr.co.cking.ticket.domain.TicketQuizRewardExecution;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import kr.co.cking.ticket.repository.TicketEarnRequestRepository;
import kr.co.cking.ticket.repository.TicketQuizRewardExecutionRepository;
import kr.co.cking.ticket.repository.TicketQuizRewardRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Quiz 보상 실행권 발급의 DB Transaction(이슈 #533). 한 Transaction에서 전역 EARN request → Quiz 요청 → 최근 실행 순서로
 * 잠근 뒤 판정하고, 실행권을 얻으면 새 실행 행(STARTED)을 만들고 요청을 EXECUTING으로 바꾼다. Redis는 호출하지 않는다.
 *
 * <p>새 실행권은 다음에서만 발급한다.
 * <ul>
 *   <li>RECEIVED이고 실행 이력이 없다: 순번 1</li>
 *   <li>RETRYABLE_FAILURE이고 가장 최근 실행이 결과를 기록했으며(FINISHED), 무반영이 확인됐고, 그 결과가 재시도 가능한
 *       분류다: 순번 + 1</li>
 * </ul>
 * 그 밖의 상태는 아무것도 바꾸지 않고 상태에 맞는 결과를 돌려준다. 같은 요청의 발급은 Quiz 요청 행 잠금으로 직렬화되고,
 * DB에서도 결과가 기록되지 않은 실행은 요청마다 하나만 있을 수 있다(V62 {@code uk_ticket_quiz_reward_execution_started}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizRewardExecutionStore {

    private final TicketEarnRequestRepository earnRequestRepository;
    private final TicketQuizRewardRequestRepository quizRewardRequestRepository;
    private final TicketQuizRewardExecutionRepository executionRepository;
    private final Clock clock;

    @Transactional
    public QuizRewardExecutionClaim claim(QuizRewardCommand command) {
        String requestId = command.rewardRequestId().toString();
        String fingerprint = command.computeFingerprint();
        Optional<TicketEarnRequest> earnRequest = earnRequestRepository.findByRequestIdForUpdate(requestId);
        if (earnRequest.isEmpty() || !earnRequest.get().hasSameRequest(fingerprint, QuizRewardCommand.SOURCE)) {
            return mismatch("전역 EARN request가 없거나 Quiz 요청과 다릅니다", requestId);
        }
        Optional<TicketQuizRewardRequest> found = quizRewardRequestRepository.findByRewardRequestIdForUpdate(requestId);
        if (found.isEmpty() || !sameInput(found.get(), command, fingerprint)) {
            return mismatch("Quiz 보상 요청이 없거나 입력이 다릅니다", requestId);
        }
        TicketQuizRewardRequest request = found.get();
        // 전역 request는 원장 반영과 같은 Transaction에서만 ACCEPTED가 된다.
        if ((earnRequest.get().getStatus() == TicketEarnRequestStatus.ACCEPTED)
                != (request.getStatus() == QuizRewardRequestStatus.APPLIED)) {
            return mismatch("전역 EARN request와 Quiz 요청의 상태가 맞지 않습니다", requestId);
        }
        Optional<TicketQuizRewardExecution> latest =
                executionRepository.findFirstByQuizRewardRequestIdOrderByExecutionSeqDesc(
                        request.getQuizRewardRequestId());

        return switch (request.getStatus()) {
            case RECEIVED -> latest.isEmpty()
                    ? start(request, 1)
                    : mismatch("RECEIVED인데 실행 이력이 있습니다", requestId);
            case RETRYABLE_FAILURE -> latest.filter(QuizRewardExecutionStore::confirmedRetryable).isPresent()
                    ? start(request, latest.get().getExecutionSeq() + 1)
                    : QuizRewardExecutionClaim.rejected(Outcome.NOT_RETRYABLE);
            case EXECUTING -> latest.filter(TicketQuizRewardExecution::isStarted).isPresent()
                    ? QuizRewardExecutionClaim.rejected(Outcome.ALREADY_EXECUTING)
                    : mismatch("EXECUTING인데 진행 중인 실행이 없습니다", requestId);
            case ACCEPTED -> QuizRewardExecutionClaim.rejected(Outcome.ALREADY_ACCEPTED);
            case UNKNOWN -> QuizRewardExecutionClaim.rejected(Outcome.UNKNOWN);
            case APPLIED -> QuizRewardExecutionClaim.rejected(Outcome.ALREADY_APPLIED);
        };
    }

    /** 실행 행을 먼저 만들고(고유 제약 위반이면 여기서 실패해 롤백된다) 요청을 EXECUTING으로 바꾼다. */
    private QuizRewardExecutionClaim start(TicketQuizRewardRequest request, int executionSeq) {
        String executionToken = UUID.randomUUID().toString();
        executionRepository.saveAndFlush(TicketQuizRewardExecution.start(
                request.getQuizRewardRequestId(), executionSeq, executionToken, clock.instant()));
        request.startExecution();
        return QuizRewardExecutionClaim.claimed(request.getQuizRewardRequestId(), executionToken, executionSeq);
    }

    /** 결과가 기록됐고, 무반영이 확인됐고, 그 분류가 재시도 가능한 실행인지. */
    private static boolean confirmedRetryable(TicketQuizRewardExecution execution) {
        if (execution.isStarted() || !execution.isNotExecutedConfirmed() || execution.getOutcome() == null) {
            return false;
        }
        try {
            QuizRedisEarnOutcome outcome = QuizRedisEarnOutcome.valueOf(execution.getOutcome());
            return outcome.confirmedNotExecuted() && outcome.retryable();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean sameInput(TicketQuizRewardRequest request, QuizRewardCommand command, String fingerprint) {
        return request.getAttemptId().equals(command.attemptId())
                && request.getMemberId().equals(command.memberId())
                && request.getCreatorId().equals(command.creatorId())
                && request.getCorrectCount() == command.correctCount()
                && request.hasSamePayload(QuizRewardCommand.FINGERPRINT_VERSION, fingerprint);
    }

    private static QuizRewardExecutionClaim mismatch(String reason, String requestId) {
        log.error("Quiz 보상 실행권을 발급하지 않습니다: {}. rewardRequestId={}", reason, requestId);
        return QuizRewardExecutionClaim.rejected(Outcome.MISMATCH);
    }
}
