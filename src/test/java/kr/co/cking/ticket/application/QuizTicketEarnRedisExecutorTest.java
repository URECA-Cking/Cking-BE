package kr.co.cking.ticket.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnResult;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Quiz Redis EARN 어댑터의 KEYS·ARGV 계약, Lua 응답 해석, 예외 분류를 실제 Redis 없이 검증한다.
 * Lua 자체의 동작은 CI 전용 {@code QuizTicketEarnLuaIntegrationTest}가 검증한다.
 */
class QuizTicketEarnRedisExecutorTest {

    private static final String STREAM_KEY = "stream:ticket-earned";
    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private static QuizEarnEvent event() {
        String fingerprint = new QuizRewardCommand(100L, 7L, 10L, 3, REQUEST_ID).computeFingerprint();
        return new QuizEarnEvent(REQUEST_ID, 100L, 7L, 10L, 3, 3L, QuizRewardCommand.FINGERPRINT_VERSION, fingerprint,
                "token-1");
    }

    private static QuizRedisEarnResult executeReturning(List<?> reply) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn((List) reply);
        return new QuizTicketEarnRedisExecutor(redisTemplate, new DefaultRedisScript<List>(), STREAM_KEY)
                .execute(event());
    }

    private static QuizRedisEarnResult executeThrowing(RuntimeException exception) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(exception);
        return new QuizTicketEarnRedisExecutor(redisTemplate, new DefaultRedisScript<List>(), STREAM_KEY)
                .execute(event());
    }

    @Test
    void 기존_Balance_maintenance_키와_Quiz_guard_키를_넘기고_Lua_ARGV_순서로_값을_넘긴다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of("EARN_ACCEPTED", "1-0", "5", "1"));
        QuizEarnEvent event = event();

        new QuizTicketEarnRedisExecutor(redisTemplate, script, STREAM_KEY).execute(event);

        verify(redisTemplate).execute(eq(script),
                eq(List.of("idem:quiz-reward:" + REQUEST_ID, TicketRedisKeys.balance(10L, 7L),
                        TicketRedisKeys.maintenance(10L, 7L))),
                eq(REQUEST_ID.toString()), eq("100"), eq("7"), eq("10"), eq("3"), eq("3"),
                eq(QuizRewardCommand.FINGERPRINT_VERSION), eq(event.fingerprint()), eq("token-1"), eq(STREAM_KEY));
    }

    @Test
    void Lua가_발행하는_필드는_QuizEarnEvent_v1_필드와_이름_순서_값이_같다() throws IOException {
        String lua = readLua();
        Map<String, Integer> argvIndex = new HashMap<>();
        Matcher locals = Pattern.compile("local (\\w+)\\s*=\\s*ARGV\\[(\\d+)]").matcher(lua);
        while (locals.find()) {
            argvIndex.put(locals.group(1), Integer.parseInt(locals.group(2)));
        }
        // XADD streamKey '*' 다음부터가 필드·값 쌍이다.
        String xadd = lua.substring(lua.indexOf("'*',", lua.indexOf("redis.pcall('XADD'")),
                lua.indexOf("if isError(streamId)"));
        Matcher pairs = Pattern.compile("'(\\w+)', ('[^']*'|\\w+)").matcher(xadd);
        List<String> argv = QuizTicketEarnRedisExecutor.argv(event(), STREAM_KEY);
        Map<String, String> published = new LinkedHashMap<>();
        while (pairs.find()) {
            String value = pairs.group(2);
            published.put(pairs.group(1), value.startsWith("'")
                    ? value.substring(1, value.length() - 1)
                    : argv.get(argvIndex.get(value) - 1));
        }

        assertThat(published).containsExactlyEntriesOf(event().toStreamFields());
        assertThat(QuizEarnEvent.fromStreamFields(published)).isEqualTo(event());
    }

    @Test
    void 정상_실행은_ACCEPTED와_Stream_ID_적립후잔액을_돌려준다() {
        QuizRedisEarnResult result = executeReturning(List.of("EARN_ACCEPTED", "1700000000000-0", "15", "1"));

        assertThat(result).isEqualTo(new QuizRedisEarnResult(
                QuizRedisEarnOutcome.ACCEPTED, "1700000000000-0", 15L, null, null));
        assertThat(result.outcome().retryable()).isFalse();
    }

    @Test
    void guard_완료_기록이_실패해도_적립_발행은_ACCEPTED이고_detail로_알린다() {
        QuizRedisEarnResult result = executeReturning(List.of("EARN_ACCEPTED", "1-0", "15", "0"));

        assertThat(result.outcome()).isEqualTo(QuizRedisEarnOutcome.ACCEPTED);
        assertThat(result.detail()).isEqualTo("GUARD_NOT_COMPLETED");
    }

    @Test
    void 중복_요청은_ALREADY_ACCEPTED이며_DB_반영이나_재시도를_뜻하지_않는다() {
        QuizRedisEarnResult result = executeReturning(List.of("ALREADY_ACCEPTED", "1-0", "token-1"));

        assertThat(result).isEqualTo(new QuizRedisEarnResult(
                QuizRedisEarnOutcome.ALREADY_ACCEPTED, "1-0", null, "token-1", null));
        assertThat(result.outcome().confirmedNotExecuted()).isFalse();
        assertThat(result.outcome().retryable()).isFalse();
    }

    @Test
    void 변경_없음이_확인된_결과만_재시도_가능으로_분류한다() {
        assertOutcome(List.of("BALANCE_MAINTENANCE"), QuizRedisEarnOutcome.BALANCE_MAINTENANCE, true);
        assertOutcome(List.of("BALANCE_NOT_LOADED"), QuizRedisEarnOutcome.BALANCE_NOT_LOADED, true);
        assertOutcome(List.of("NOT_EXECUTED", "XADD_FAILED", "WRONGTYPE"), QuizRedisEarnOutcome.RETRYABLE_FAILURE, true);
        assertOutcome(List.of("NOT_EXECUTED", "INCRBY_FAILED", "ERR"), QuizRedisEarnOutcome.RETRYABLE_FAILURE, true);
        assertOutcome(List.of("REQUEST_ID_CONFLICT"), QuizRedisEarnOutcome.REQUEST_ID_CONFLICT, false);
        assertOutcome(List.of("INVALID_REQUEST", "amount"), QuizRedisEarnOutcome.INVALID_REQUEST, false);
        for (QuizRedisEarnOutcome outcome : List.of(QuizRedisEarnOutcome.BALANCE_MAINTENANCE,
                QuizRedisEarnOutcome.BALANCE_NOT_LOADED, QuizRedisEarnOutcome.RETRYABLE_FAILURE,
                QuizRedisEarnOutcome.REQUEST_ID_CONFLICT, QuizRedisEarnOutcome.INVALID_REQUEST)) {
            assertThat(outcome.confirmedNotExecuted()).isTrue();
        }
        assertThat(executeReturning(List.of("NOT_EXECUTED", "XADD_FAILED", "WRONGTYPE")).detail())
                .isEqualTo("XADD_FAILED");
    }

    @Test
    void XADD_실패의_보상을_확인하지_못하면_UNKNOWN이다() {
        QuizRedisEarnResult compensationFailed =
                executeReturning(List.of("EARN_STATUS_UNKNOWN", "XADD_FAILED_COMPENSATION_FAILED"));
        QuizRedisEarnResult guardLeft = executeReturning(List.of("EARN_STATUS_UNKNOWN", "XADD_FAILED_GUARD_LEFT"));

        assertThat(compensationFailed).isEqualTo(QuizRedisEarnResult.unknown("XADD_FAILED_COMPENSATION_FAILED"));
        assertThat(guardLeft).isEqualTo(QuizRedisEarnResult.unknown("XADD_FAILED_GUARD_LEFT"));
        assertThat(QuizRedisEarnOutcome.UNKNOWN.retryable()).isFalse();
        assertThat(QuizRedisEarnOutcome.UNKNOWN.confirmedNotExecuted()).isFalse();
    }

    @Test
    void guard만_PROCESSING으로_남아_있으면_UNKNOWN이다() {
        assertThat(executeReturning(List.of("EARN_STATUS_UNKNOWN", "GUARD_PROCESSING")))
                .isEqualTo(QuizRedisEarnResult.unknown("GUARD_PROCESSING"));
    }

    @Test
    void 타임아웃과_명령_전송_후_오류는_재시도_가능으로_단정하지_않고_UNKNOWN이다() {
        assertThat(executeThrowing(new QueryTimeoutException("timeout")))
                .isEqualTo(QuizRedisEarnResult.unknown("TIMEOUT"));
        assertThat(executeThrowing(new RedisSystemException("reset", new RuntimeException("connection reset"))))
                .isEqualTo(QuizRedisEarnResult.unknown("REDIS_ERROR"));
        assertThat(executeThrowing(new RedisConnectionFailureException("refused")))
                .isEqualTo(QuizRedisEarnResult.unknown("REDIS_ERROR"));
    }

    @Test
    void 응답이_없거나_형식이_다르면_UNKNOWN이다() {
        assertThat(executeReturning(null)).isEqualTo(QuizRedisEarnResult.unknown("EMPTY_REPLY"));
        assertThat(executeReturning(List.of())).isEqualTo(QuizRedisEarnResult.unknown("EMPTY_REPLY"));
        assertThat(executeReturning(List.of("EARN_ACCEPTED"))).isEqualTo(
                QuizRedisEarnResult.unknown("MALFORMED_EARN_ACCEPTED"));
        assertThat(executeReturning(List.of("EARN_ACCEPTED", "1-0", "x", "1"))).isEqualTo(
                QuizRedisEarnResult.unknown("MALFORMED_EARN_ACCEPTED"));
        assertThat(executeReturning(List.of("ALREADY_ACCEPTED"))).isEqualTo(
                QuizRedisEarnResult.unknown("MALFORMED_ALREADY_ACCEPTED"));
        assertThat(executeReturning(List.of("EARN_PROCESSING_FAILED"))).isEqualTo(
                QuizRedisEarnResult.unknown("UNRECOGNIZED_CODE"));
    }

    @Test
    void NOT_EXECUTED는_확인된_무반영_사유와_Redis_오류가_모두_있을_때만_재시도_가능하다() {
        assertUnknown(List.of("NOT_EXECUTED"));
        assertUnknown(List.of("NOT_EXECUTED", "XADD_FAILED"));
        assertUnknown(List.of("NOT_EXECUTED", "XADD_FAILED", " "));
        assertUnknown(List.of("NOT_EXECUTED", "SOMETHING_ELSE", "ERR"));
        assertUnknown(List.of("NOT_EXECUTED", "XADD_FAILED", "ERR", "extra"));
        for (String reason : List.of("GUARD_RESERVE_FAILED", "BALANCE_READ_FAILED", "INCRBY_FAILED", "XADD_FAILED")) {
            assertThat(executeReturning(List.of("NOT_EXECUTED", reason, "ERR")))
                    .isEqualTo(QuizRedisEarnResult.of(QuizRedisEarnOutcome.RETRYABLE_FAILURE, reason));
        }
    }

    @Test
    void Stream_ID_형식이_틀리면_UNKNOWN이다() {
        for (String streamId : List.of("invalid-stream-id", "1", "1-", "-1", "01-0", "1-01", "1-0-0", " 1-0",
                "18446744073709551616-0", "1-18446744073709551616")) {
            assertUnknown(List.of("EARN_ACCEPTED", streamId, "5", "1"));
            assertUnknown(List.of("ALREADY_ACCEPTED", streamId, "token-1"));
        }
        assertThat(executeReturning(List.of("EARN_ACCEPTED", "18446744073709551615-18446744073709551615", "5", "1"))
                .outcome()).isEqualTo(QuizRedisEarnOutcome.ACCEPTED);
        assertThat(executeReturning(List.of("EARN_ACCEPTED", "0-1", "5", "1")).outcome())
                .isEqualTo(QuizRedisEarnOutcome.ACCEPTED);
    }

    @Test
    void ALREADY_ACCEPTED는_저장된_실행권이_있어야_한다() {
        assertUnknown(List.of("ALREADY_ACCEPTED", "1-0"));
        assertUnknown(List.of("ALREADY_ACCEPTED", "1-0", " "));
        assertUnknown(List.of("ALREADY_ACCEPTED", "1-0", "x".repeat(257)));
        assertUnknown(List.of("ALREADY_ACCEPTED", "1-0", "token-1", "extra"));
    }

    @Test
    void ALREADY_ACCEPTED의_저장된_실행권이_이번_실행권과_달라도_그대로_돌려준다() {
        QuizRedisEarnResult result = executeReturning(List.of("ALREADY_ACCEPTED", "1-0", "token-other"));

        assertThat(result).isEqualTo(new QuizRedisEarnResult(
                QuizRedisEarnOutcome.ALREADY_ACCEPTED, "1-0", null, "token-other", null));
    }

    @Test
    void EARN_ACCEPTED의_잔액은_정규_정수이고_이번_지급량_이상이어야_한다() {
        assertUnknown(List.of("EARN_ACCEPTED", "invalid-stream-id", "-1"));
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "-1", "1"));
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "0", "1"));
        // 지급량 3보다 작으면 적립 후 잔액일 수 없다.
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "2", "1"));
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "05", "1"));
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "+5", "1"));
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "9.007199254741e+15", "1"));
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "9223372036854775808", "1"));
        assertThat(executeReturning(List.of("EARN_ACCEPTED", "1-0", "3", "1")).balanceAfter()).isEqualTo(3L);
        assertThat(executeReturning(List.of("EARN_ACCEPTED", "1-0", "9007199254740995", "1")).balanceAfter())
                .isEqualTo(9_007_199_254_740_995L);
        assertThat(executeReturning(List.of("EARN_ACCEPTED", "1-0", "9223372036854775807", "1")).balanceAfter())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void guardCompleted는_정확히_0_또는_1이어야_한다() {
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "5"));
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "5", "2"));
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "5", "true"));
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "5", " 1"));
        assertUnknown(List.of("EARN_ACCEPTED", "1-0", "5", "1", "extra"));
    }

    @Test
    void 결과_코드별_필드_개수와_사유가_다르면_UNKNOWN이다() {
        assertUnknown(List.of("REQUEST_ID_CONFLICT", "extra"));
        assertUnknown(List.of("BALANCE_MAINTENANCE", "extra"));
        assertUnknown(List.of("BALANCE_NOT_LOADED", "extra"));
        assertUnknown(List.of("INVALID_REQUEST"));
        assertUnknown(List.of("INVALID_REQUEST", "unknown-field"));
        assertUnknown(Arrays.asList("EARN_ACCEPTED", "1-0", 5L, "1"));
        assertThat(executeReturning(List.of("INVALID_REQUEST", "balanceKey")))
                .isEqualTo(QuizRedisEarnResult.of(QuizRedisEarnOutcome.INVALID_REQUEST, "balanceKey"));
    }

    @Test
    void UNKNOWN은_어떤_사유든_자동_재시도_대상이_아니다() {
        for (List<?> reply : List.of(List.of("NOT_EXECUTED"), List.of("EARN_STATUS_UNKNOWN"),
                List.of("EARN_STATUS_UNKNOWN", "XADD_FAILED_COMPENSATION_UNVERIFIED"), List.of("?"))) {
            QuizRedisEarnResult result = executeReturning(reply);
            assertThat(result.outcome()).isEqualTo(QuizRedisEarnOutcome.UNKNOWN);
            assertThat(result.outcome().retryable()).isFalse();
            assertThat(result.outcome().confirmedNotExecuted()).isFalse();
        }
    }

    @Test
    void 결과_DTO는_outcome별_불변조건을_어기면_만들_수_없다() {
        assertThatThrownBy(() -> new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, "bad", 5L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, "1-0", -1L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, "1-0", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, "1-0", 5L, "token", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, "1-0", 5L, null, "OTHER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRedisEarnResult(QuizRedisEarnOutcome.ALREADY_ACCEPTED, "1-0", null, null,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRedisEarnResult(QuizRedisEarnOutcome.ALREADY_ACCEPTED, "1-0", 5L, "token",
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuizRedisEarnResult.of(QuizRedisEarnOutcome.RETRYABLE_FAILURE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuizRedisEarnResult.of(QuizRedisEarnOutcome.RETRYABLE_FAILURE, "UNLISTED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuizRedisEarnResult.of(QuizRedisEarnOutcome.INVALID_REQUEST, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRedisEarnResult(QuizRedisEarnOutcome.UNKNOWN, "1-0", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRedisEarnResult(null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Lua는_ID를_Long_범위로_문자열_비교하고_잔액을_Lua_number로_바꾸지_않는다() throws IOException {
        String lua = readLua();

        assertThat(lua).contains("'9223372036854775807'")
                .doesNotContain("tostring(newBalance)")
                .doesNotContain("newBalance - amount");
    }

    @Test
    void Mission_Lua와_Quiz_Lua는_같은_Stream_Balance_maintenance_키_형식을_쓰고_guard는_분리된다() throws IOException {
        String lua = readLua();

        assertThat(lua).contains("'ticket:balance:' .. creatorId .. ':' .. memberId")
                .contains("'ticket:maint:' .. creatorId .. ':' .. memberId")
                .contains("'idem:quiz-reward:' .. requestId");
        assertThat(TicketRedisKeys.balance(10L, 7L)).isEqualTo("ticket:balance:10:7");
        assertThat(TicketRedisKeys.maintenance(10L, 7L)).isEqualTo("ticket:maint:10:7");
        assertThat(TicketRedisKeys.idemQuizReward(REQUEST_ID.toString()))
                .isNotEqualTo(TicketRedisKeys.idemMission(REQUEST_ID.toString()))
                .isNotEqualTo(TicketRedisKeys.idemMissionOnce(REQUEST_ID.toString()));
    }

    private static void assertOutcome(List<?> reply, QuizRedisEarnOutcome expected, boolean retryable) {
        QuizRedisEarnResult result = executeReturning(reply);
        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.outcome().retryable()).isEqualTo(retryable);
        assertThat(Arrays.asList(result.streamId(), result.balanceAfter(), result.executionToken()))
                .containsOnlyNulls();
    }

    private static void assertUnknown(List<?> reply) {
        QuizRedisEarnResult result = executeReturning(reply);
        assertThat(result.outcome()).as("reply=%s", reply).isEqualTo(QuizRedisEarnOutcome.UNKNOWN);
        assertThat(Arrays.asList(result.streamId(), result.balanceAfter(), result.executionToken()))
                .as("reply=%s", reply).containsOnlyNulls();
    }

    private static String readLua() throws IOException {
        try (InputStream in = new ClassPathResource("scripts/quiz-ticket-earn.lua").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
