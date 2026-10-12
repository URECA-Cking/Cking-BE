package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import kr.co.cking.ticket.domain.TicketQuizRewardExecution;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import kr.co.cking.ticket.repository.TicketQuizRewardExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 메시지의 실행권이 이 요청에 발급·저장된 실행이고, 그 실행이 Stream에 메시지를 발행했을 수 있을 때만 원장 반영을
 * 허용한다(이슈 #533). 메시지 내용과 저장된 요청의 대조(요청 ID·attempt·회원·Creator·정답 수·fingerprint)는
 * {@link QuizRewardLedgerService}가 먼저 한다.
 *
 * <ul>
 *   <li>요청이 EXECUTING(Worker가 결과를 아직 기록하지 않음)·ACCEPTED·UNKNOWN일 때만 허용한다. RECEIVED·
 *       RETRYABLE_FAILURE는 이 요청에서 발행된 메시지가 있을 수 없어 거부한다</li>
 *   <li>실행이 이 요청의 것이어야 하고, 무반영으로 확인된 실행이면 거부한다</li>
 *   <li>결과가 기록된 실행은 이 실행권으로 메시지를 발행한 결과(ACCEPTED)이거나 확인할 수 없는 결과(UNKNOWN)여야
 *       한다. ALREADY_ACCEPTED와 guard 대조 실패로 기록된 UNKNOWN은 이 실행권이 발행하지 않았으므로 거부한다</li>
 * </ul>
 * 원장 반영 Transaction 안에서 Quiz 요청 다음에 실행 행을 잠근다(전역 claim → Quiz 요청 → 실행 → Balance).
 */
@Component
@RequiredArgsConstructor
public class StoredExecutionQuizRewardExecutionPolicy implements QuizRewardExecutionPolicy {

    private static final Set<QuizRewardRequestStatus> APPLICABLE = Set.of(QuizRewardRequestStatus.EXECUTING,
            QuizRewardRequestStatus.ACCEPTED, QuizRewardRequestStatus.UNKNOWN);
    /** {@link QuizRewardExecutionStore}가 guard 대조에 실패한 ALREADY_ACCEPTED를 UNKNOWN으로 남길 때의 사유 접두어. */
    private static final String ALREADY_ACCEPTED_REASON_PREFIX = "ALREADY_ACCEPTED_";

    private final TicketQuizRewardExecutionRepository executionRepository;

    @Override
    public void requireApplicable(TicketQuizRewardRequest request, QuizEarnEvent event) {
        if (!APPLICABLE.contains(request.getStatus())) {
            throw rejected("반영할 수 없는 요청 상태입니다. status=" + request.getStatus(), request);
        }
        TicketQuizRewardExecution execution = executionRepository.findByExecutionToken(event.executionToken())
                .orElseThrow(() -> rejected("저장된 실행권이 아닙니다", request));
        if (!execution.getQuizRewardRequestId().equals(request.getQuizRewardRequestId())) {
            throw rejected("다른 요청의 실행권입니다", request);
        }
        if (execution.isNotExecutedConfirmed()) {
            throw rejected("무반영으로 확인된 실행의 실행권입니다", request);
        }
        if (!execution.isStarted() && !publishedByThisExecution(execution)) {
            throw rejected("이 실행권으로 발행된 메시지일 수 없습니다. outcome=" + execution.getOutcome(), request);
        }
    }

    private static boolean publishedByThisExecution(TicketQuizRewardExecution execution) {
        if (QuizRedisEarnOutcome.ACCEPTED.name().equals(execution.getOutcome())) {
            return true;
        }
        return QuizRedisEarnOutcome.UNKNOWN.name().equals(execution.getOutcome())
                && (execution.getReason() == null || !execution.getReason().startsWith(ALREADY_ACCEPTED_REASON_PREFIX));
    }

    private static IllegalStateException rejected(String reason, TicketQuizRewardRequest request) {
        return new IllegalStateException("Quiz 보상 EARN을 반영하지 않습니다: " + reason + ". rewardRequestId="
                + request.getRewardRequestId());
    }
}
