package kr.co.cking.ticket.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnResult;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Redis에서 {@code quiz-ticket-earn.lua}를 실행해 적립·중복·보정 락·Balance 미적재·XADD 실패 보상을 검증한다
 * (이슈 #533). {@code TicketEarnServiceImplTest}처럼 Spring 컨텍스트 없이 localhost Redis에 직접 연결하며, CI의
 * Redis 서비스에서만 실행한다. 전용 Stream 키와 충돌 없는 테스트 ID를 쓰고 만든 키만 지운다.
 */
class QuizTicketEarnLuaIntegrationTest {

    private static final String STREAM_KEY = "stream:ticket-earned:quiz-lua-test";
    private static final Long MEMBER_ID = 97533L;
    private static final Long CREATOR_ID = 97534L;
    private static final Long ATTEMPT_ID = 97535L;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private QuizTicketEarnRedisExecutor executor;
    private UUID requestId;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/quiz-ticket-earn.lua"));
        script.setResultType(List.class);
        executor = new QuizTicketEarnRedisExecutor(redisTemplate, script, STREAM_KEY);
        requestId = UUID.randomUUID();
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        connectionFactory.destroy();
    }

    private void cleanUp() {
        redisTemplate.delete(List.of(balanceKey(), maintenanceKey(), guardKey(), STREAM_KEY));
    }

    private String balanceKey() {
        return TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID);
    }

    private String maintenanceKey() {
        return TicketRedisKeys.maintenance(CREATOR_ID, MEMBER_ID);
    }

    private String guardKey() {
        return TicketRedisKeys.idemQuizReward(requestId.toString());
    }

    private QuizEarnEvent event(int correctCount, String token) {
        String fingerprint = new QuizRewardCommand(ATTEMPT_ID, MEMBER_ID, CREATOR_ID, correctCount, requestId)
                .computeFingerprint();
        return new QuizEarnEvent(requestId, ATTEMPT_ID, MEMBER_ID, CREATOR_ID, correctCount, correctCount,
                QuizRewardCommand.FINGERPRINT_VERSION, fingerprint, token);
    }

    private String balance() {
        return redisTemplate.opsForValue().get(balanceKey());
    }

    private List<MapRecord<String, Object, Object>> records() {
        Long size = redisTemplate.opsForStream().size(STREAM_KEY);
        return size == null || size == 0 ? List.of() : redisTemplate.opsForStream().range(STREAM_KEY, Range.unbounded());
    }

    private static Map<String, String> asStrings(Map<Object, Object> fields) {
        return fields.entrySet().stream()
                .collect(Collectors.toMap(e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue())));
    }

    @Test
    void 잔액을_늘리고_Dispatcher가_읽는_v1_필드로_발행한다() {
        redisTemplate.opsForValue().set(balanceKey(), "10");
        QuizEarnEvent event = event(3, "token-1");

        QuizRedisEarnResult result = executor.execute(event);

        assertThat(result.outcome()).isEqualTo(QuizRedisEarnOutcome.ACCEPTED);
        assertThat(result.balanceAfter()).isEqualTo(13L);
        assertThat(result.detail()).isNull();
        assertThat(balance()).isEqualTo("13");
        List<MapRecord<String, Object, Object>> records = records();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getId().getValue()).isEqualTo(result.streamId());
        Map<String, String> fields = asStrings(records.get(0).getValue());
        assertThat(fields).isEqualTo(event.toStreamFields());
        assertThat(QuizEarnEvent.fromStreamFields(fields)).isEqualTo(event);
    }

    @Test
    void 같은_rewardRequestId는_다시_적립하지_않고_처음_Stream_ID를_돌려준다() {
        redisTemplate.opsForValue().set(balanceKey(), "0");
        QuizRedisEarnResult first = executor.execute(event(2, "token-1"));

        QuizRedisEarnResult again = executor.execute(event(2, "token-2"));

        assertThat(again.outcome()).isEqualTo(QuizRedisEarnOutcome.ALREADY_ACCEPTED);
        assertThat(again.streamId()).isEqualTo(first.streamId());
        assertThat(again.executionToken()).isEqualTo("token-1");
        assertThat(balance()).isEqualTo("2");
        assertThat(records()).hasSize(1);
    }

    @Test
    void 같은_rewardRequestId에_다른_내용이면_충돌이고_아무것도_바꾸지_않는다() {
        redisTemplate.opsForValue().set(balanceKey(), "0");
        executor.execute(event(2, "token-1"));

        QuizRedisEarnResult conflict = executor.execute(event(3, "token-1"));

        assertThat(conflict.outcome()).isEqualTo(QuizRedisEarnOutcome.REQUEST_ID_CONFLICT);
        assertThat(balance()).isEqualTo("2");
        assertThat(records()).hasSize(1);
    }

    @Test
    void 보정_락이_있으면_guard도_만들지_않고_멈춘다() {
        redisTemplate.opsForValue().set(balanceKey(), "5");
        redisTemplate.opsForValue().set(maintenanceKey(), "lock-token");

        QuizRedisEarnResult result = executor.execute(event(2, "token-1"));

        assertThat(result.outcome()).isEqualTo(QuizRedisEarnOutcome.BALANCE_MAINTENANCE);
        assertThat(balance()).isEqualTo("5");
        assertThat(redisTemplate.hasKey(guardKey())).isFalse();
        assertThat(records()).isEmpty();
    }

    @Test
    void Balance_키가_없으면_0에서_적립하지_않는다() {
        QuizRedisEarnResult result = executor.execute(event(2, "token-1"));

        assertThat(result.outcome()).isEqualTo(QuizRedisEarnOutcome.BALANCE_NOT_LOADED);
        assertThat(redisTemplate.hasKey(balanceKey())).isFalse();
        assertThat(redisTemplate.hasKey(guardKey())).isFalse();
        assertThat(records()).isEmpty();
    }

    @Test
    void XADD가_실패하면_잔액과_guard를_되돌리고_같은_요청으로_다시_실행할_수_있다() {
        redisTemplate.opsForValue().set(balanceKey(), "7");
        // Stream 키가 String이면 XADD가 WRONGTYPE으로 실패한다.
        redisTemplate.opsForValue().set(STREAM_KEY, "not-a-stream");

        QuizRedisEarnResult failed = executor.execute(event(2, "token-1"));

        assertThat(failed.outcome()).isEqualTo(QuizRedisEarnOutcome.RETRYABLE_FAILURE);
        assertThat(failed.detail()).isEqualTo("XADD_FAILED");
        assertThat(balance()).isEqualTo("7");
        assertThat(redisTemplate.hasKey(guardKey())).isFalse();

        redisTemplate.delete(STREAM_KEY);
        QuizRedisEarnResult retried = executor.execute(event(2, "token-1"));

        assertThat(retried.outcome()).isEqualTo(QuizRedisEarnOutcome.ACCEPTED);
        assertThat(balance()).isEqualTo("9");
        assertThat(records()).hasSize(1);
    }

    @Test
    void INCRBY가_실패하면_guard를_지우고_재시도_가능한_실패다() {
        redisTemplate.opsForValue().set(balanceKey(), "not-a-number");

        QuizRedisEarnResult result = executor.execute(event(2, "token-1"));

        assertThat(result.outcome()).isEqualTo(QuizRedisEarnOutcome.RETRYABLE_FAILURE);
        assertThat(result.detail()).isEqualTo("INCRBY_FAILED");
        assertThat(balance()).isEqualTo("not-a-number");
        assertThat(redisTemplate.hasKey(guardKey())).isFalse();
        assertThat(records()).isEmpty();
    }

    @Test
    void PROCESSING_guard만_남아_있으면_다시_적립하지_않고_UNKNOWN이다() {
        redisTemplate.opsForValue().set(balanceKey(), "4");
        QuizEarnEvent event = event(2, "token-1");
        redisTemplate.opsForValue().set(guardKey(),
                "{\"status\":\"PROCESSING\",\"fingerprint\":\"" + event.fingerprint() + "\"}");

        QuizRedisEarnResult result = executor.execute(event);

        assertThat(result).isEqualTo(QuizRedisEarnResult.unknown("GUARD_PROCESSING"));
        assertThat(balance()).isEqualTo("4");
        assertThat(records()).isEmpty();
    }

    @Test
    void 완료된_guard가_있으면_Balance_키가_없어도_중복으로_답하고_적립하지_않는다() {
        redisTemplate.opsForValue().set(balanceKey(), "0");
        QuizRedisEarnResult first = executor.execute(event(1, "token-1"));
        redisTemplate.delete(balanceKey());

        QuizRedisEarnResult again = executor.execute(event(1, "token-1"));

        assertThat(again.outcome()).isEqualTo(QuizRedisEarnOutcome.ALREADY_ACCEPTED);
        assertThat(again.streamId()).isEqualTo(first.streamId());
        assertThat(redisTemplate.hasKey(balanceKey())).isFalse();
    }

    private List<?> executeRaw(List<String> keys, List<String> argv) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/quiz-ticket-earn.lua"));
        script.setResultType(List.class);
        return redisTemplate.execute(script, keys, argv.toArray());
    }

    @Test
    void ID는_Long_MAX_VALUE까지_허용한다() {
        redisTemplate.opsForValue().set(balanceKey(), "0");
        String fingerprint = new QuizRewardCommand(Long.MAX_VALUE, MEMBER_ID, CREATOR_ID, 1, requestId)
                .computeFingerprint();
        QuizEarnEvent event = new QuizEarnEvent(requestId, Long.MAX_VALUE, MEMBER_ID, CREATOR_ID, 1, 1L,
                QuizRewardCommand.FINGERPRINT_VERSION, fingerprint, "token-1");

        QuizRedisEarnResult result = executor.execute(event);

        assertThat(result.outcome()).isEqualTo(QuizRedisEarnOutcome.ACCEPTED);
        assertThat(asStrings(records().get(0).getValue()).get("attemptId")).isEqualTo("9223372036854775807");
    }

    @Test
    void Long_범위를_넘거나_양수가_아닌_ID는_거부하고_Redis를_바꾸지_않는다() {
        redisTemplate.opsForValue().set(balanceKey(), "3");
        List<String> argv = QuizTicketEarnRedisExecutor.argv(event(1, "token-1"), STREAM_KEY);
        List<String> keys = QuizTicketEarnRedisExecutor.keys(event(1, "token-1"));

        for (String attemptId : List.of("9223372036854775808", "9999999999999999999", "10000000000000000000",
                "0", "-1", "01", "1.0", "")) {
            List<String> bad = new java.util.ArrayList<>(argv);
            bad.set(1, attemptId);

            assertThat(executeRaw(keys, bad)).as(attemptId).isEqualTo(List.of("INVALID_REQUEST", "attemptId"));
        }
        assertThat(balance()).isEqualTo("3");
        assertThat(redisTemplate.hasKey(guardKey())).isFalse();
        assertThat(records()).isEmpty();
    }

    @Test
    void 큰_잔액도_GET_원문_기준으로_정확히_적립하고_돌려준다() {
        // 2^53 + 1. double로 바꾸면 9007199254740992가 된다.
        redisTemplate.opsForValue().set(balanceKey(), "9007199254740993");

        QuizRedisEarnResult result = executor.execute(event(2, "token-1"));

        assertThat(result.outcome()).isEqualTo(QuizRedisEarnOutcome.ACCEPTED);
        assertThat(result.balanceAfter()).isEqualTo(9_007_199_254_740_995L);
        assertThat(balance()).isEqualTo("9007199254740995");
    }

    @Test
    void 큰_잔액에서_XADD가_실패하면_원문과_같은_값으로_복원된_것을_확인한_뒤에만_재시도_가능하다() {
        redisTemplate.opsForValue().set(balanceKey(), "9007199254740993");
        redisTemplate.opsForValue().set(STREAM_KEY, "not-a-stream");

        QuizRedisEarnResult failed = executor.execute(event(2, "token-1"));

        assertThat(failed.outcome()).isEqualTo(QuizRedisEarnOutcome.RETRYABLE_FAILURE);
        assertThat(failed.detail()).isEqualTo("XADD_FAILED");
        assertThat(balance()).isEqualTo("9007199254740993");
        assertThat(redisTemplate.hasKey(guardKey())).isFalse();
    }

    @Test
    void 적립하면_Long_범위를_넘는_잔액은_INCRBY_실패로_바꾸지_않는다() {
        redisTemplate.opsForValue().set(balanceKey(), "9223372036854775806");

        QuizRedisEarnResult result = executor.execute(event(2, "token-1"));

        assertThat(result.outcome()).isEqualTo(QuizRedisEarnOutcome.RETRYABLE_FAILURE);
        assertThat(result.detail()).isEqualTo("INCRBY_FAILED");
        assertThat(balance()).isEqualTo("9223372036854775806");
        assertThat(redisTemplate.hasKey(guardKey())).isFalse();
        assertThat(records()).isEmpty();
    }

    @Test
    void 키와_인자가_다른_회원을_가리키면_아무것도_바꾸지_않는다() {
        redisTemplate.opsForValue().set(balanceKey(), "3");
        QuizEarnEvent event = event(1, "token-1");
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/quiz-ticket-earn.lua"));
        script.setResultType(List.class);
        List<String> swappedKeys = List.of(guardKey(), TicketRedisKeys.balance(MEMBER_ID, CREATOR_ID),
                maintenanceKey());

        List<?> reply = redisTemplate.execute(script, swappedKeys,
                QuizTicketEarnRedisExecutor.argv(event, STREAM_KEY).toArray());

        assertThat(reply).isEqualTo(List.of("INVALID_REQUEST", "balanceKey"));
        assertThat(balance()).isEqualTo("3");
        assertThat(redisTemplate.hasKey(guardKey())).isFalse();
    }
}
