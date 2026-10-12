package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.QuizRewardExecutionStore.FinishOutcome;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnResult;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import kr.co.cking.ticket.repository.TicketQuizRewardRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

/**
 * 접수된 Quiz 보상 요청 하나를 Redis EARN까지 실행한다(이슈 #533). 실행권 하나로 Lua를 많아야 한 번 부른다.
 *
 * <ol>
 *   <li>저장된 요청을 읽어 입력을 다시 만든다</li>
 *   <li>실행권을 발급한다. 발급 Transaction은 반환 전에 커밋된다. 실행권을 얻지 못하면(실행 중·ACCEPTED·UNKNOWN·APPLIED·
 *       무반영 증거 없음·불일치) Redis를 부르지 않는다</li>
 *   <li>Redis Balance 키가 있으면 그대로 쓴다. 없으면 같은 회원·Creator에 원장으로 확정되지 않은 Quiz 지급(다른 요청의
 *       EXECUTING·ACCEPTED·UNKNOWN, 무반영이 확인되지 않은 과거 실행)이 없을 때만 {@link TicketBalanceKeyLoader}로 DB 기준
 *       적재를 시도한다. 그런 지급이 있으면 Redis에만 남았을 수 있는 적립을 DB 잔액으로 덮게 되므로 적재하지 않는다.
 *       Loader는 보정 락을 잡고 미반영 SPEND·EARN 메시지가 없을 때만 적재한다. 키 확인·미확정 지급 확인·적재가 안 되면
 *       Lua를 부르지 않고, 무반영인 {@link QuizRedisEarnOutcome#BALANCE_NOT_LOADED}로 기록한다</li>
 *   <li>Lua를 한 번 부르고 결과를 분류한다. 결과가 불확실하면 UNKNOWN이다</li>
 *   <li>별도의 짧은 Transaction에서 결과를 기록한다({@link QuizRewardExecutionStore#finish})</li>
 * </ol>
 * Redis를 부르는 동안 DB Transaction이나 행 잠금을 잡지 않는다. 같은 실행권으로 Lua를 다시 부르지 않으며, 다시 실행은
 * 결과가 기록된 뒤 새 실행권으로만 한다. 결과 기록이 실패하면 요청이 EXECUTING으로 남아 새 실행권도 발급되지 않는다.
 *
 * <p>Quiz 도메인 연동(응시·채점·호출) 전에 지급이 시작되지 않도록 기본값은 꺼짐이다
 * ({@code cking.ticket.quiz-reward.worker-enabled}). 꺼져 있으면 아무것도 바꾸지 않고 DISABLED로 끝난다.
 */
@Slf4j
@Service
public class QuizRewardEarnWorker {

    static final String BALANCE_CHECK_FAILED = "BALANCE_CHECK_FAILED";
    static final String BALANCE_LOAD_FAILED = "BALANCE_LOAD_FAILED";
    static final String UNSETTLED_QUIZ_REWARD = "UNSETTLED_QUIZ_REWARD";

    private final TicketQuizRewardRequestRepository quizRewardRequestRepository;
    private final QuizRewardExecutionClaimService claimService;
    private final QuizRewardExecutionStore executionStore;
    private final QuizTicketEarnRedisExecutor redisExecutor;
    private final TicketBalanceKeyLoader balanceKeyLoader;
    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;

    public QuizRewardEarnWorker(
            TicketQuizRewardRequestRepository quizRewardRequestRepository,
            QuizRewardExecutionClaimService claimService,
            QuizRewardExecutionStore executionStore,
            QuizTicketEarnRedisExecutor redisExecutor,
            TicketBalanceKeyLoader balanceKeyLoader,
            StringRedisTemplate redisTemplate,
            @Value("${cking.ticket.quiz-reward.worker-enabled:false}") boolean enabled
    ) {
        this.quizRewardRequestRepository = quizRewardRequestRepository;
        this.claimService = claimService;
        this.executionStore = executionStore;
        this.redisExecutor = redisExecutor;
        this.balanceKeyLoader = balanceKeyLoader;
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
    }

    public QuizRewardWorkResult run(UUID rewardRequestId) {
        if (!enabled) {
            return new QuizRewardWorkResult(QuizRewardWorkResult.Status.DISABLED, null, null, null);
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Quiz 보상 Worker는 Transaction 밖에서 실행해야 합니다.");
        }
        Optional<TicketQuizRewardRequest> stored =
                quizRewardRequestRepository.findByRewardRequestId(rewardRequestId.toString());
        if (stored.isEmpty()) {
            return new QuizRewardWorkResult(QuizRewardWorkResult.Status.REQUEST_NOT_FOUND, null, null, null);
        }
        TicketQuizRewardRequest request = stored.get();
        QuizRewardCommand command = new QuizRewardCommand(request.getAttemptId(), request.getMemberId(),
                request.getCreatorId(), request.getCorrectCount(), rewardRequestId);

        QuizRewardExecutionClaim claim = claimService.claim(command);
        if (claim.outcome() != QuizRewardExecutionClaim.Outcome.CLAIMED) {
            return new QuizRewardWorkResult(QuizRewardWorkResult.Status.NOT_CLAIMED, claim.outcome(), null, null);
        }

        QuizRedisEarnResult redisResult = executeOnce(command, claim.quizRewardRequestId(), claim.executionToken());
        try {
            FinishOutcome finish = executionStore.finish(rewardRequestId, claim.executionToken(), redisResult);
            return new QuizRewardWorkResult(QuizRewardWorkResult.Status.RECORDED, claim.outcome(),
                    redisResult.outcome(), finish);
        } catch (RuntimeException e) {
            // 요청은 EXECUTING, 실행은 결과 없이 남는다. 새 실행권이 발급되지 않으므로 다시 적립하지 않는다.
            log.error("Quiz 보상 실행 결과를 기록하지 못했습니다. rewardRequestId={}, executionSeq={}, redisOutcome={}",
                    rewardRequestId, claim.executionSeq(), redisResult.outcome(), e);
            return new QuizRewardWorkResult(QuizRewardWorkResult.Status.RECORD_FAILED, claim.outcome(),
                    redisResult.outcome(), null);
        }
    }

    /** Balance 키를 준비한 뒤 Lua를 많아야 한 번 부른다. 준비가 확인되지 않으면 부르지 않는다. */
    private QuizRedisEarnResult executeOnce(QuizRewardCommand command, Long quizRewardRequestId,
                                            String executionToken) {
        Boolean exists;
        try {
            exists = redisTemplate.hasKey(TicketRedisKeys.balance(command.creatorId(), command.memberId()));
        } catch (RuntimeException e) {
            log.warn("Quiz 보상 실행 전 Redis Balance 키를 확인하지 못해 실행하지 않습니다. rewardRequestId={}",
                    command.rewardRequestId(), e);
            return QuizRedisEarnResult.of(QuizRedisEarnOutcome.BALANCE_NOT_LOADED, BALANCE_CHECK_FAILED);
        }
        if (exists == null) {
            return QuizRedisEarnResult.of(QuizRedisEarnOutcome.BALANCE_NOT_LOADED, BALANCE_CHECK_FAILED);
        }
        if (!exists) {
            QuizRedisEarnResult notLoaded = loadBalanceKeyIfSettled(command, quizRewardRequestId, executionToken);
            if (notLoaded != null) {
                return notLoaded;
            }
        }
        QuizEarnEvent event = new QuizEarnEvent(command.rewardRequestId(), command.attemptId(), command.memberId(),
                command.creatorId(), command.correctCount(), command.amount(), QuizRewardCommand.FINGERPRINT_VERSION,
                command.computeFingerprint(), executionToken);
        return redisExecutor.execute(event);
    }

    /**
     * 미확정 Quiz 지급이 없을 때만 Balance 키를 적재한다. 적재했으면 null, 아니면 Lua를 부르지 않고 기록할 무반영 결과다.
     *
     * <p>확인과 적재 사이에 같은 회원·Creator의 다른 Quiz 실행이 시작돼도, 키가 없는 동안 Lua는 적립하지 않고
     * BALANCE_NOT_LOADED로 끝나며 적재 중에는 보정 락 때문에 BALANCE_MAINTENANCE로 끝난다. 적재 뒤 실행은 적재된 잔액
     * 위에 적립한다. 그래서 확인 이후 새로 생긴 실행은 적재 값으로 덮이지 않는다. 확인 시점에 이미 있던 미확정 지급은
     * 이 확인으로 막는다.
     */
    private QuizRedisEarnResult loadBalanceKeyIfSettled(QuizRewardCommand command, Long quizRewardRequestId,
                                                        String executionToken) {
        long unsettled;
        try {
            unsettled = quizRewardRequestRepository.countUnsettled(command.memberId(), command.creatorId(),
                    quizRewardRequestId, executionToken);
        } catch (RuntimeException e) {
            log.warn("Quiz 보상 실행 전 미확정 지급을 확인하지 못해 실행하지 않습니다. rewardRequestId={}",
                    command.rewardRequestId(), e);
            return QuizRedisEarnResult.of(QuizRedisEarnOutcome.BALANCE_NOT_LOADED, BALANCE_CHECK_FAILED);
        }
        if (unsettled > 0) {
            log.warn("원장으로 확정되지 않은 Quiz 지급이 있어 Redis Balance 키를 적재하지 않습니다. rewardRequestId={}, "
                    + "unsettled={}", command.rewardRequestId(), unsettled);
            return QuizRedisEarnResult.of(QuizRedisEarnOutcome.BALANCE_NOT_LOADED, UNSETTLED_QUIZ_REWARD);
        }
        if (!loadBalanceKey(command)) {
            log.warn("Quiz 보상 실행 전 Redis Balance 키를 적재하지 못해 실행하지 않습니다. rewardRequestId={}",
                    command.rewardRequestId());
            return QuizRedisEarnResult.of(QuizRedisEarnOutcome.BALANCE_NOT_LOADED, BALANCE_LOAD_FAILED);
        }
        return null;
    }

    /** 적재 중 DB 오류 등으로 실패해도 Lua를 부르지 않도록 false로 끝낸다. 적재 자체는 Lua 전 단계라 무반영이다. */
    private boolean loadBalanceKey(QuizRewardCommand command) {
        try {
            return balanceKeyLoader.load(CouponType.CREATOR, command.creatorId(), command.memberId());
        } catch (RuntimeException e) {
            log.warn("Quiz 보상 실행 전 Redis Balance 키 적재 중 오류가 발생했습니다. rewardRequestId={}",
                    command.rewardRequestId(), e);
            return false;
        }
    }
}
