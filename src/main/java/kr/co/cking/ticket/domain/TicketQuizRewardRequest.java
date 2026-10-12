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
 * Quiz 응시(attempt) 보상 Ticket 지급 요청의 durable 접수 기록(이슈 #533). attempt마다 하나이며 Mission을 참조하지
 * 않는다. 같은 rewardRequestId는 전역 {@code ticket_earn_request}(reward_policy=QUIZ)에도 같은 Transaction에서 선점한다.
 * 보상량은 정답 1개당 1장이라 정답 수와 같다.
 */
@Entity
@Table(name = "ticket_quiz_reward_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketQuizRewardRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long quizRewardRequestId;

    private String rewardRequestId;
    private Long attemptId;
    private Long memberId;
    private Long creatorId;
    private int correctCount;
    private Long amount;
    private String fingerprintVersion;
    private String payloadFingerprint;

    @Enumerated(EnumType.STRING)
    private QuizRewardRequestStatus status;

    private Instant createdAt;
    private Instant appliedAt;

    /** RECEIVED로 접수한다. 입력 검증과 fingerprint 계산은 호출자가 마친 값이다. */
    public TicketQuizRewardRequest(String rewardRequestId, Long attemptId, Long memberId, Long creatorId,
                                   int correctCount, String fingerprintVersion, String payloadFingerprint,
                                   Instant createdAt) {
        if (correctCount <= 0) {
            throw new IllegalArgumentException("Quiz 보상 정답 수는 1 이상이어야 합니다: " + correctCount);
        }
        this.rewardRequestId = Objects.requireNonNull(rewardRequestId, "rewardRequestId");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.creatorId = Objects.requireNonNull(creatorId, "creatorId");
        this.correctCount = correctCount;
        this.amount = (long) correctCount;
        this.fingerprintVersion = Objects.requireNonNull(fingerprintVersion, "fingerprintVersion");
        this.payloadFingerprint = Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
        this.status = QuizRewardRequestStatus.RECEIVED;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * 실행권 발급과 같은 Transaction에서 EXECUTING으로 바꾼다. 아직 실행하지 않았거나(RECEIVED) 이전 실행이 무반영으로
     * 확인된 재시도 가능한 실패(RETRYABLE_FAILURE)에서만 허용한다. 무반영 증거 확인은 호출자 몫이다. 호출자는 행을 잠근
     * 뒤 부른다.
     */
    public void startExecution() {
        if (status != QuizRewardRequestStatus.RECEIVED && status != QuizRewardRequestStatus.RETRYABLE_FAILURE) {
            throw new IllegalStateException("Quiz 보상 실행을 시작할 수 없는 상태입니다. status=" + status
                    + ", rewardRequestId=" + rewardRequestId);
        }
        this.status = QuizRewardRequestStatus.EXECUTING;
    }

    /**
     * 실행 결과 기록과 같은 Transaction에서 EXECUTING을 결과 상태로 바꾼다. 결과 상태는 ACCEPTED·RETRYABLE_FAILURE·UNKNOWN
     * 중 하나다. EXECUTING이 아니면(APPLIED 포함) 바꾸지 않고 예외다. 호출자는 행을 잠근 뒤 부른다.
     */
    public void recordExecutionResult(QuizRewardRequestStatus next) {
        if (status != QuizRewardRequestStatus.EXECUTING) {
            throw new IllegalStateException("실행 중이 아닌 Quiz 보상 요청에 실행 결과를 기록할 수 없습니다. status=" + status
                    + ", rewardRequestId=" + rewardRequestId);
        }
        if (next != QuizRewardRequestStatus.ACCEPTED && next != QuizRewardRequestStatus.RETRYABLE_FAILURE
                && next != QuizRewardRequestStatus.UNKNOWN) {
            throw new IllegalArgumentException("Quiz 보상 실행 결과 상태가 아닙니다: " + next);
        }
        this.status = next;
    }

    /**
     * Ticket 원장·잔액 반영과 같은 Transaction에서 APPLIED로 바꾼다. APPLIED는 끝 상태라 되돌리거나 다시 적용하지
     * 않는다. 호출자는 행을 잠근 뒤 부른다.
     */
    public void markApplied(Instant appliedAt) {
        if (status == QuizRewardRequestStatus.APPLIED) {
            throw new IllegalStateException("이미 반영한 Quiz 보상 요청입니다. rewardRequestId=" + rewardRequestId);
        }
        this.status = QuizRewardRequestStatus.APPLIED;
        this.appliedAt = Objects.requireNonNull(appliedAt, "appliedAt");
    }

    /** 같은 fingerprint 규칙과 같은 입력인지. rewardRequestId는 비교하지 않는다. */
    public boolean hasSamePayload(String fingerprintVersion, String payloadFingerprint) {
        return this.fingerprintVersion.equals(fingerprintVersion) && this.payloadFingerprint.equals(payloadFingerprint);
    }
}
