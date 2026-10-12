package kr.co.cking.ticket.application.dto;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Quiz 보상 Redis EARN 실행 결과(이슈 #533). outcome마다 채울 수 있는 값이 정해져 있으며 어기면 만들 수 없다.
 *
 * <ul>
 *   <li>{@link QuizRedisEarnOutcome#ACCEPTED}: streamId·balanceAfter(1 이상) 필수. detail은 없거나
 *       {@value #GUARD_NOT_COMPLETED}</li>
 *   <li>{@link QuizRedisEarnOutcome#ALREADY_ACCEPTED}: streamId·executionToken 필수. 저장된 실행권이 이번 실행권과 달라도
 *       그대로 담는다. 어떤 실행권의 결과인지는 호출자가 판단한다</li>
 *   <li>{@link QuizRedisEarnOutcome#RETRYABLE_FAILURE}: detail은 Lua가 변경을 모두 되돌렸다고 확인한 사유 중 하나</li>
 *   <li>{@link QuizRedisEarnOutcome#INVALID_REQUEST}: detail(위반 항목) 필수</li>
 *   <li>그 밖: streamId·balanceAfter·executionToken 없음</li>
 * </ul>
 *
 * @param outcome         결과 분류
 * @param streamId        Redis Stream ID({@code ms-seq}, 각각 unsigned 64-bit 10진수)
 * @param balanceAfter    이번 적립 후 Redis 잔액
 * @param executionToken  guard에 저장된 실행권
 * @param detail          Lua가 알려준 이유. 없으면 null
 */
public record QuizRedisEarnResult(
        QuizRedisEarnOutcome outcome,
        String streamId,
        Long balanceAfter,
        String executionToken,
        String detail
) {

    /** guard 완료 기록이 실패했지만 적립·발행은 끝났다. */
    public static final String GUARD_NOT_COMPLETED = "GUARD_NOT_COMPLETED";
    /** Lua가 변경을 모두 되돌렸다고 확인한 NOT_EXECUTED 사유. 이 밖의 사유는 재시도 가능으로 보지 않는다. */
    public static final Set<String> NOT_EXECUTED_REASONS =
            Set.of("GUARD_RESERVE_FAILED", "BALANCE_READ_FAILED", "INCRBY_FAILED", "XADD_FAILED");
    /** Lua 입력 계약 위반 항목. */
    public static final Set<String> INVALID_REQUEST_REASONS = Set.of("requestId", "attemptId", "memberId", "creatorId",
            "correctCount", "amount", "fingerprintVersion", "fingerprint", "executionToken", "streamKey", "guardKey",
            "balanceKey", "maintenanceKey");
    /** Lua와 같은 실행권 길이 상한. */
    public static final int MAX_EXECUTION_TOKEN_LENGTH = 256;

    private static final Pattern STREAM_ID = Pattern.compile("(0|[1-9][0-9]{0,19})-(0|[1-9][0-9]{0,19})");

    public QuizRedisEarnResult {
        if (outcome == null) {
            throw new IllegalArgumentException("Quiz Redis EARN 결과에 outcome이 없습니다.");
        }
        switch (outcome) {
            case ACCEPTED -> require(isStreamId(streamId) && balanceAfter != null && balanceAfter >= 1
                    && executionToken == null && (detail == null || GUARD_NOT_COMPLETED.equals(detail)), outcome);
            case ALREADY_ACCEPTED -> require(isStreamId(streamId) && balanceAfter == null
                    && isExecutionToken(executionToken) && detail == null, outcome);
            case RETRYABLE_FAILURE -> require(noValues(streamId, balanceAfter, executionToken)
                    && detail != null && NOT_EXECUTED_REASONS.contains(detail), outcome);
            case INVALID_REQUEST -> require(noValues(streamId, balanceAfter, executionToken)
                    && detail != null && INVALID_REQUEST_REASONS.contains(detail), outcome);
            default -> require(noValues(streamId, balanceAfter, executionToken), outcome);
        }
    }

    public static QuizRedisEarnResult of(QuizRedisEarnOutcome outcome, String detail) {
        return new QuizRedisEarnResult(outcome, null, null, null, detail);
    }

    public static QuizRedisEarnResult unknown(String detail) {
        return of(QuizRedisEarnOutcome.UNKNOWN, detail);
    }

    /** {@code ms-seq} 형식이고 두 값 모두 앞자리 0 없는 unsigned 64-bit 10진수인지. */
    public static boolean isStreamId(String value) {
        if (value == null || !STREAM_ID.matcher(value).matches()) {
            return false;
        }
        String[] parts = value.split("-");
        try {
            Long.parseUnsignedLong(parts[0]);
            Long.parseUnsignedLong(parts[1]);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 공백만이 아니고 Lua와 같은 길이 상한 이내인지. */
    public static boolean isExecutionToken(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_EXECUTION_TOKEN_LENGTH;
    }

    private static boolean noValues(String streamId, Long balanceAfter, String executionToken) {
        return streamId == null && balanceAfter == null && executionToken == null;
    }

    private static void require(boolean valid, QuizRedisEarnOutcome outcome) {
        if (!valid) {
            throw new IllegalArgumentException("Quiz Redis EARN 결과가 " + outcome + "의 조건과 맞지 않습니다.");
        }
    }
}
