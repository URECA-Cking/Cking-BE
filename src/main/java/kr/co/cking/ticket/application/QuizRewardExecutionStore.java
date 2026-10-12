package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.QuizRewardExecutionClaim.Outcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnResult;
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
import java.util.regex.Pattern;

/**
 * Quiz 보상 실행권 발급과 실행 결과 기록의 DB Transaction(이슈 #533). 한 Transaction에서 전역 EARN request → Quiz 요청 → 최근 실행 순서로
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

    private static final Pattern SAFE_REASON = Pattern.compile("[A-Za-z0-9_]{1,64}");

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

    /**
     * Redis 실행 결과를 그 실행권의 실행 기록과 요청 상태에 남긴다. 발급과 같은 순서(전역 EARN request → Quiz 요청 →
     * 실행)로 잠그는 짧은 Transaction 하나다. Redis는 호출하지 않는다.
     *
     * <ul>
     *   <li>실행권이 이 요청의 가장 최근 실행이고 아직 결과가 없을 때만 기록한다. 다른 요청의 실행권, 이미 결과를 기록한
     *       실행, 늦게 도착한 이전 실행의 결과는 아무것도 바꾸지 않고 {@link FinishOutcome#REJECTED}다</li>
     *   <li>요청이 이미 APPLIED면(원장 반영이 먼저 끝났다) 실행 기록만 끝내고 요청 상태는 그대로 둔다</li>
     *   <li>요청이 EXECUTING이면 결과 분류에 맞는 상태로 바꾼다. 그 밖의 상태는 REJECTED다</li>
     * </ul>
     *
     * <p>ALREADY_ACCEPTED는 guard에 남은 실행권이 이 요청의 이전 실행이고, 그 실행이 무반영으로 기록되지 않았고, 그
     * 실행에 Stream ID가 남아 있다면 guard의 Stream ID와 같을 때만 ACCEPTED로 본다. 아니면 어느 적립인지 확정할 수 없어
     * 실행 기록을 UNKNOWN으로 남긴다.
     */
    @Transactional
    public FinishOutcome finish(UUID rewardRequestId, String executionToken, QuizRedisEarnResult result) {
        String requestId = rewardRequestId.toString();
        if (earnRequestRepository.findByRequestIdForUpdate(requestId).isEmpty()) {
            return rejected("전역 EARN request가 없습니다", requestId);
        }
        Optional<TicketQuizRewardRequest> found = quizRewardRequestRepository.findByRewardRequestIdForUpdate(requestId);
        if (found.isEmpty()) {
            return rejected("Quiz 보상 요청이 없습니다", requestId);
        }
        TicketQuizRewardRequest request = found.get();
        Long quizRewardRequestId = request.getQuizRewardRequestId();
        Optional<TicketQuizRewardExecution> current = executionRepository.findByExecutionToken(executionToken)
                .filter(execution -> execution.getQuizRewardRequestId().equals(quizRewardRequestId))
                .filter(TicketQuizRewardExecution::isStarted);
        Optional<Long> latestId = executionRepository
                .findFirstByQuizRewardRequestIdOrderByExecutionSeqDesc(quizRewardRequestId)
                .map(TicketQuizRewardExecution::getQuizRewardExecutionId);
        if (current.isEmpty() || !latestId.equals(Optional.of(current.get().getQuizRewardExecutionId()))) {
            return rejected("진행 중인 최근 실행의 실행권이 아닙니다", requestId);
        }
        boolean applied = request.getStatus() == QuizRewardRequestStatus.APPLIED;
        if (!applied && request.getStatus() != QuizRewardRequestStatus.EXECUTING) {
            return rejected("실행 중이 아닌 요청입니다. status=" + request.getStatus(), requestId);
        }

        RecordedResult recorded = recordedResultOf(result, quizRewardRequestId);
        current.get().finish(recorded.outcome().name(), recorded.reason(), recorded.streamId(),
                recorded.outcome().confirmedNotExecuted(), clock.instant());
        if (applied) {
            if (recorded.outcome() != QuizRedisEarnOutcome.ACCEPTED
                    && recorded.outcome() != QuizRedisEarnOutcome.ALREADY_ACCEPTED) {
                log.error("원장 반영이 끝난 Quiz 보상 요청에 적립이 아닌 실행 결과가 도착했습니다. rewardRequestId={}, "
                        + "outcome={}", requestId, recorded.outcome());
            }
            return FinishOutcome.RECORDED_REQUEST_ALREADY_APPLIED;
        }
        request.recordExecutionResult(requestStatusOf(recorded.outcome()));
        return FinishOutcome.RECORDED;
    }

    /** 실행 기록에 남길 결과. ALREADY_ACCEPTED의 실행권이 이 요청의 적립으로 확인되지 않으면 UNKNOWN으로 바꾼다. */
    private RecordedResult recordedResultOf(QuizRedisEarnResult result, Long quizRewardRequestId) {
        if (result.outcome() == QuizRedisEarnOutcome.ALREADY_ACCEPTED) {
            Optional<TicketQuizRewardExecution> referenced = executionRepository
                    .findByExecutionToken(result.executionToken())
                    .filter(execution -> execution.getQuizRewardRequestId().equals(quizRewardRequestId))
                    .filter(execution -> !execution.isNotExecutedConfirmed());
            if (referenced.isEmpty()) {
                log.error("guard의 실행권이 이 요청의 적립 실행으로 확인되지 않습니다. quizRewardRequestId={}, streamId={}",
                        quizRewardRequestId, result.streamId());
                return new RecordedResult(QuizRedisEarnOutcome.UNKNOWN, "ALREADY_ACCEPTED_TOKEN_UNVERIFIED", null);
            }
            // 그 실행이 Stream ID를 남겼다면(ACCEPTED·ALREADY_ACCEPTED) guard가 알려준 Stream ID와 같아야 한다.
            // 결과 없이 끝난 실행이나 UNKNOWN처럼 Stream ID가 없는 실행은 비교할 값이 없어 실행권 확인만으로 판단한다.
            String recordedStreamId = referenced.get().getStreamId();
            if (recordedStreamId != null && !recordedStreamId.equals(result.streamId())) {
                log.error("guard의 Stream ID가 실행 기록과 다릅니다. quizRewardRequestId={}, recorded={}, guard={}",
                        quizRewardRequestId, recordedStreamId, result.streamId());
                return new RecordedResult(QuizRedisEarnOutcome.UNKNOWN, "ALREADY_ACCEPTED_STREAM_MISMATCH", null);
            }
        }
        return new RecordedResult(result.outcome(), safeReason(result.detail()), result.streamId());
    }

    /**
     * 실행 결과에 따른 요청 상태. 무반영이 확인되고 다시 실행해도 되는 결과만 RETRYABLE_FAILURE다. 무반영이지만
     * 같은 입력으로 다시 실행해도 안 되는 결과(REQUEST_ID_CONFLICT·INVALID_REQUEST)는 확인이 필요하므로 UNKNOWN이다.
     */
    private static QuizRewardRequestStatus requestStatusOf(QuizRedisEarnOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED, ALREADY_ACCEPTED -> QuizRewardRequestStatus.ACCEPTED;
            case BALANCE_MAINTENANCE, BALANCE_NOT_LOADED, RETRYABLE_FAILURE -> QuizRewardRequestStatus.RETRYABLE_FAILURE;
            case REQUEST_ID_CONFLICT, INVALID_REQUEST, UNKNOWN -> QuizRewardRequestStatus.UNKNOWN;
        };
    }

    /** 사유는 영문·숫자·밑줄 코드만 남긴다. Redis 원본 오류나 긴 값은 저장하지 않는다. */
    static String safeReason(String detail) {
        if (detail == null) {
            return null;
        }
        return SAFE_REASON.matcher(detail).matches() ? detail : "UNRECOGNIZED_REASON";
    }

    private static FinishOutcome rejected(String reason, String requestId) {
        log.error("Quiz 보상 실행 결과를 기록하지 않습니다: {}. rewardRequestId={}", reason, requestId);
        return FinishOutcome.REJECTED;
    }

    /** 실행 결과 기록 결과. */
    public enum FinishOutcome {
        /** 실행 기록을 끝내고 요청 상태를 결과에 맞게 바꿨다. */
        RECORDED,
        /** 원장 반영이 먼저 끝나(APPLIED) 실행 기록만 끝냈다. 요청 상태는 그대로다. */
        RECORDED_REQUEST_ALREADY_APPLIED,
        /** 이 실행권으로 기록할 수 없어 아무것도 바꾸지 않았다. */
        REJECTED
    }

    private record RecordedResult(QuizRedisEarnOutcome outcome, String reason, String streamId) {
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
