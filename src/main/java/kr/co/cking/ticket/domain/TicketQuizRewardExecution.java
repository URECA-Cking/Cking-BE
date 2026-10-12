package kr.co.cking.ticket.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * Quiz 보상 지급 요청의 Redis EARN 실행 한 번(이슈 #533). 실행권({@code executionToken})은 이 행으로 Redis 호출 전에
 * 발급·커밋한다. 이미 만든 행은 지우거나 다른 실행으로 덮어쓰지 않으며, 다시 실행하면 순번을 늘린 새 행을 만든다.
 *
 * <p>{@code outcome}은 {@code QuizRedisEarnOutcome} 이름이다.
 */
@Entity
@Table(name = "ticket_quiz_reward_execution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketQuizRewardExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long quizRewardExecutionId;

    private Long quizRewardRequestId;
    private int executionSeq;
    private String executionToken;

    @Enumerated(EnumType.STRING)
    private QuizRewardExecutionStatus status;

    private String outcome;
    private String reason;
    private String streamId;
    private boolean notExecutedConfirmed;
    private Instant startedAt;
    private Instant finishedAt;

    /** 결과가 아직 없는 실행을 시작한다. */
    public static TicketQuizRewardExecution start(Long quizRewardRequestId, int executionSeq, String executionToken,
                                                  Instant startedAt) {
        if (executionSeq <= 0) {
            throw new IllegalArgumentException("Quiz 보상 실행 순번은 1 이상이어야 합니다: " + executionSeq);
        }
        TicketQuizRewardExecution execution = new TicketQuizRewardExecution();
        execution.quizRewardRequestId = Objects.requireNonNull(quizRewardRequestId, "quizRewardRequestId");
        execution.executionSeq = executionSeq;
        execution.executionToken = Objects.requireNonNull(executionToken, "executionToken");
        execution.status = QuizRewardExecutionStatus.STARTED;
        execution.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        return execution;
    }

    /**
     * 결과를 기록해 FINISHED로 끝낸다. 이미 끝난 실행은 다시 기록하지 않는다. 결과 분류별 조건(Stream ID·무반영 확인)은
     * 호출자가 맞춰 넘기고 V62 CHECK가 최종 확인한다.
     *
     * @param outcome              {@code QuizRedisEarnOutcome} 이름
     * @param reason               안전한 사유 코드. 없으면 null
     * @param streamId             ACCEPTED·ALREADY_ACCEPTED의 Stream ID. 그 밖에는 null
     * @param notExecutedConfirmed 이 실행이 Redis를 바꾸지 않았음이 확인됐는지
     */
    public void finish(String outcome, String reason, String streamId, boolean notExecutedConfirmed,
                       Instant finishedAt) {
        if (status != QuizRewardExecutionStatus.STARTED) {
            throw new IllegalStateException("이미 결과를 기록한 Quiz 보상 실행입니다. executionToken=" + executionToken);
        }
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(finishedAt, "finishedAt");
        this.status = QuizRewardExecutionStatus.FINISHED;
        this.outcome = outcome;
        this.reason = reason;
        this.streamId = streamId;
        this.notExecutedConfirmed = notExecutedConfirmed;
        this.finishedAt = finishedAt;
    }

    public boolean isStarted() {
        return status == QuizRewardExecutionStatus.STARTED;
    }
}
