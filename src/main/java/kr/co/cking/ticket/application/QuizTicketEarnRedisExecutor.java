package kr.co.cking.ticket.application;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnResult;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code quiz-ticket-earn.lua}로 Quiz 보상의 Redis Balance 증가와 {@code stream:ticket-earned} 발행을 한 번에
 * 실행한다(이슈 #533). 입력은 검증을 마친 {@link QuizEarnEvent}이며 Stream에 실리는 필드도 같은 값이다.
 *
 * <p>결과가 확실하지 않으면 {@link QuizRedisEarnOutcome#UNKNOWN}이다. 명령을 보낸 뒤의 예외(타임아웃·연결 끊김
 * 등)는 Redis가 이미 실행했을 수 있으므로 모두 UNKNOWN으로 두고, 스크립트가 직접 "변경 없음"을 확인해 준 경우만
 * 재시도 가능한 실패로 분류한다. 이 클래스는 상태를 저장하거나 다시 실행하지 않는다. 언제 실행하고 결과를 어떻게
 * DB 상태로 남길지는 호출자가 정한다.
 */
@Slf4j
@Component
public class QuizTicketEarnRedisExecutor {

    /** 부호·앞자리 0 없는 10진 정수. Lua가 Redis GET 원문을 그대로 돌려준다. */
    private static final Pattern CANONICAL_BALANCE = Pattern.compile("0|[1-9][0-9]{0,18}");

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> quizTicketEarnLuaScript;
    private final String streamKey;

    public QuizTicketEarnRedisExecutor(
            StringRedisTemplate redisTemplate,
            @Qualifier("quizTicketEarnLuaScript") DefaultRedisScript<List> quizTicketEarnLuaScript,
            @Value("${cking.ticket.earn-stream-key:stream:ticket-earned}") String streamKey
    ) {
        this.redisTemplate = redisTemplate;
        this.quizTicketEarnLuaScript = quizTicketEarnLuaScript;
        this.streamKey = streamKey;
    }

    public QuizRedisEarnResult execute(QuizEarnEvent event) {
        List<?> reply;
        try {
            reply = redisTemplate.execute(quizTicketEarnLuaScript, keys(event), argv(event, streamKey).toArray());
        } catch (QueryTimeoutException e) {
            log.error("Quiz EARN Lua 실행이 타임아웃되어 적립 여부를 알 수 없습니다. requestId={}", event.requestId(), e);
            return QuizRedisEarnResult.unknown("TIMEOUT");
        } catch (RuntimeException e) {
            log.error("Quiz EARN Lua 실행 중 오류로 적립 여부를 알 수 없습니다. requestId={}", event.requestId(), e);
            return QuizRedisEarnResult.unknown("REDIS_ERROR");
        }
        QuizRedisEarnResult result = parse(reply, event.amount());
        if (result.outcome() == QuizRedisEarnOutcome.UNKNOWN || result.outcome() == QuizRedisEarnOutcome.INVALID_REQUEST) {
            log.error("Quiz EARN Lua 결과를 확정하지 못했습니다. requestId={}, outcome={}, detail={}",
                    event.requestId(), result.outcome(), result.detail());
        } else if (result.detail() != null) {
            log.warn("Quiz EARN Lua 결과에 확인이 필요한 정보가 있습니다. requestId={}, outcome={}, detail={}",
                    event.requestId(), result.outcome(), result.detail());
        }
        return result;
    }

    /** KEYS. 순서는 Lua의 KEYS[1..3]과 같다. */
    static List<String> keys(QuizEarnEvent event) {
        return List.of(
                TicketRedisKeys.idemQuizReward(event.requestId().toString()),
                TicketRedisKeys.balance(event.creatorId(), event.memberId()),
                TicketRedisKeys.maintenance(event.creatorId(), event.memberId()));
    }

    /** ARGV. 순서는 Lua의 ARGV[1..10]과 같다. */
    static List<String> argv(QuizEarnEvent event, String streamKey) {
        return List.of(
                event.requestId().toString(),
                String.valueOf(event.attemptId()),
                String.valueOf(event.memberId()),
                String.valueOf(event.creatorId()),
                String.valueOf(event.correctCount()),
                String.valueOf(event.amount()),
                event.fingerprintVersion(),
                event.fingerprint(),
                event.executionToken(),
                streamKey);
    }

    /**
     * Lua 응답을 결과로 바꾼다. 결과 코드마다 필드 개수·형식을 확인하고, 하나라도 다르면 실행됐을 수 있으므로
     * UNKNOWN이다. 불완전한 응답을 재시도 가능한 실패로 보지 않는다.
     *
     * @param amount 이번 요청의 지급량. 적립 후 잔액이 이보다 작으면 응답을 믿지 않는다
     */
    static QuizRedisEarnResult parse(List<?> reply, long amount) {
        if (reply == null || reply.isEmpty()) {
            return QuizRedisEarnResult.unknown("EMPTY_REPLY");
        }
        for (Object element : reply) {
            if (!(element instanceof String)) {
                return QuizRedisEarnResult.unknown("MALFORMED_REPLY");
            }
        }
        List<String> values = reply.stream().map(String.class::cast).toList();
        return switch (values.get(0)) {
            case "EARN_ACCEPTED" -> parseAccepted(values, amount);
            case "ALREADY_ACCEPTED" -> values.size() == 3
                    && QuizRedisEarnResult.isStreamId(values.get(1))
                    && QuizRedisEarnResult.isExecutionToken(values.get(2))
                    ? new QuizRedisEarnResult(QuizRedisEarnOutcome.ALREADY_ACCEPTED, values.get(1), null,
                            values.get(2), null)
                    : QuizRedisEarnResult.unknown("MALFORMED_ALREADY_ACCEPTED");
            case "REQUEST_ID_CONFLICT" -> codeOnly(values, QuizRedisEarnOutcome.REQUEST_ID_CONFLICT);
            case "BALANCE_MAINTENANCE" -> codeOnly(values, QuizRedisEarnOutcome.BALANCE_MAINTENANCE);
            case "BALANCE_NOT_LOADED" -> codeOnly(values, QuizRedisEarnOutcome.BALANCE_NOT_LOADED);
            case "INVALID_REQUEST" -> values.size() == 2
                    && QuizRedisEarnResult.INVALID_REQUEST_REASONS.contains(values.get(1))
                    ? QuizRedisEarnResult.of(QuizRedisEarnOutcome.INVALID_REQUEST, values.get(1))
                    : QuizRedisEarnResult.unknown("MALFORMED_INVALID_REQUEST");
            // 변경을 모두 되돌렸다고 Lua가 확인한 사유와 Redis 오류가 함께 있어야만 재시도 가능하다.
            case "NOT_EXECUTED" -> values.size() == 3
                    && QuizRedisEarnResult.NOT_EXECUTED_REASONS.contains(values.get(1))
                    && !values.get(2).isBlank()
                    ? QuizRedisEarnResult.of(QuizRedisEarnOutcome.RETRYABLE_FAILURE, values.get(1))
                    : QuizRedisEarnResult.unknown("MALFORMED_NOT_EXECUTED");
            case "EARN_STATUS_UNKNOWN" -> QuizRedisEarnResult.unknown(values.size() > 1 ? values.get(1) : null);
            default -> QuizRedisEarnResult.unknown("UNRECOGNIZED_CODE");
        };
    }

    private static QuizRedisEarnResult codeOnly(List<String> values, QuizRedisEarnOutcome outcome) {
        return values.size() == 1
                ? QuizRedisEarnResult.of(outcome, null)
                : QuizRedisEarnResult.unknown("MALFORMED_" + outcome.name());
    }

    private static QuizRedisEarnResult parseAccepted(List<String> values, long amount) {
        if (values.size() != 4 || !QuizRedisEarnResult.isStreamId(values.get(1))
                || !CANONICAL_BALANCE.matcher(values.get(2)).matches()) {
            return QuizRedisEarnResult.unknown("MALFORMED_EARN_ACCEPTED");
        }
        long balanceAfter;
        try {
            balanceAfter = Long.parseLong(values.get(2));
        } catch (NumberFormatException e) {
            return QuizRedisEarnResult.unknown("MALFORMED_EARN_ACCEPTED");
        }
        // 기존 잔액은 0 이상이므로 적립 후 잔액은 이번 지급량보다 작을 수 없다.
        if (balanceAfter < amount) {
            return QuizRedisEarnResult.unknown("MALFORMED_EARN_ACCEPTED");
        }
        // guard 완료 기록이 실패했어도 적립·발행은 끝났다. 이후 같은 요청은 Lua가 UNKNOWN으로 멈춘다.
        return switch (values.get(3)) {
            case "1" -> new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, values.get(1), balanceAfter, null, null);
            case "0" -> new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, values.get(1), balanceAfter, null,
                    QuizRedisEarnResult.GUARD_NOT_COMPLETED);
            default -> QuizRedisEarnResult.unknown("MALFORMED_EARN_ACCEPTED");
        };
    }
}
