package kr.co.cking.abuse.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.application.model.AbuseFeatureWindowPolicy;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.ResultClassification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.RedisConnectionFailureException;

/** RedisAbuseFeatureStore의 입력 검증·Lua 비정상 응답·더미 key 처리를 단위 검증한다. */
class RedisAbuseFeatureStoreTest {

    private static final AbuseFeatureWindowPolicy WINDOW_POLICY = new AbuseFeatureWindowPolicy(
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));

    private StringRedisTemplate redisTemplate;
    private DefaultRedisScript<List> luaScript;
    private RedisAbuseFeatureStore featureStore;

    /** 각 테스트가 독립된 RedisTemplate mock과 Feature Store를 사용하도록 초기화한다. */
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        luaScript = new DefaultRedisScript<>();
        featureStore = new RedisAbuseFeatureStore(redisTemplate, luaScript);
    }

    /** observation 또는 window policy가 없으면 Redis 호출 전에 입력을 거부한다. */
    @Test
    void 필수_입력이_없으면_거부한다() {
        assertThatThrownBy(() -> featureStore.record(null, WINDOW_POLICY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> featureStore.record(entry(), null))
                .isInstanceOf(NullPointerException.class);
    }

    /** 밀리초로 표현할 수 없는 window는 Lua에 0ms로 전달되기 전에 거부한다. */
    @Test
    void 서브밀리초_Window을_거부한다() {
        AbuseFeatureWindowPolicy subMillisecondWindowPolicy = new AbuseFeatureWindowPolicy(
                Duration.ofNanos(500), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> featureStore.record(mission(), subMillisecondWindowPolicy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Feature window는 최소 1밀리초 이상이어야 합니다.");
    }

    /** Lua가 Snapshot을 반환하지 않으면 정상 Feature 값으로 해석하지 않고 실패시킨다. */
    @Test
    void Lua가_Null_Snapshot을_반환하면_실패한다() {
        stubLuaResult(null);

        assertThatThrownBy(() -> featureStore.record(mission(), WINDOW_POLICY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Abuse Feature Lua가 완전한 Snapshot을 반환하지 않았습니다.");
    }

    /** Lua metric 개수가 계약보다 적으면 잘린 Snapshot을 반환하지 않고 실패시킨다. */
    @Test
    void Lua가_불완전한_Snapshot을_반환하면_실패한다() {
        stubLuaResult(List.of(1L));

        assertThatThrownBy(() -> featureStore.record(mission(), WINDOW_POLICY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Abuse Feature Lua가 완전한 Snapshot을 반환하지 않았습니다.");
    }

    /** Lua가 음수 metric을 반환하면 Rule Engine에 전달하지 않고 실패시킨다. */
    @Test
    void Lua가_음수_Metric을_반환하면_실패한다() {
        stubLuaResult(List.of(-1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L));

        assertThatThrownBy(() -> featureStore.record(mission(), WINDOW_POLICY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Abuse Feature Lua가 유효하지 않은 metric 값을 반환했습니다.");
    }

    /** Redis 연결이 끊기면 빈 Snapshot으로 대체하지 않고 연결 오류를 그대로 전파한다. */
    @Test
    void Redis_연결_오류를_빈_Snapshot으로_숨기지_않는다() {
        RedisConnectionFailureException failure = new RedisConnectionFailureException("Redis 연결 오류");
        when(redisTemplate.execute(
                ArgumentMatchers.<DefaultRedisScript<List>>any(), ArgumentMatchers.<String>anyList(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(failure);

        assertThatThrownBy(() -> featureStore.record(mission(), WINDOW_POLICY))
                .isSameAs(failure);
    }

    /** Feature Store Lua 실행 오류는 정상 Snapshot으로 대체하지 않고 호출자에게 전파한다. */
    @Test
    void FeatureStore_오류를_정상_Snapshot으로_숨기지_않는다() {
        RuntimeException failure = new RuntimeException("Feature Store Lua 오류");
        when(redisTemplate.execute(
                ArgumentMatchers.<DefaultRedisScript<List>>any(), ArgumentMatchers.<String>anyList(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(failure);

        assertThatThrownBy(() -> featureStore.record(mission(), WINDOW_POLICY))
                .isSameAs(failure);
    }

    /** Event Entry는 사용하지 않는 Mission business key에 SHA-256 hash를 적용하지 않고 더미 key를 전달한다. */
    @Test
    void Event_Entry는_사용하지_않는_Mission_Key에_더미_Key를_전달한다() {
        stubLuaResult(validSnapshotValues());

        AbuseFeatureSnapshot snapshot = featureStore.record(entry(), WINDOW_POLICY);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(
                eq(luaScript), keysCaptor.capture(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        assertThat(keysCaptor.getValue().get(1)).isEqualTo("abuse:v1:feature-store:unused");
        assertThat(keysCaptor.getValue().get(4)).isEqualTo("abuse:v1:feature-store:unused");
        assertThat(snapshot.valueOf(AbuseMetric.ENTRY_REQUEST_COUNT)).isEqualTo(1L);
    }

    /** Feature Store가 Lua 결과를 받을 때 사용할 공통 mock 동작을 등록한다. */
    private void stubLuaResult(List<?> result) {
        when(redisTemplate.execute(
                ArgumentMatchers.<DefaultRedisScript<List>>any(), ArgumentMatchers.<String>anyList(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result);
    }

    /** Lua의 11개 metric 순서를 만족하는 정상 Snapshot 값을 반환한다. */
    private List<Long> validSnapshotValues() {
        return List.of(0L, 0L, 1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    /** Creator Mission의 유효한 관찰 이벤트를 생성한다. */
    private AbuseObservationEvent mission() {
        return new AbuseObservationEvent(
                UUID.randomUUID(), 17L, AbuseActionType.MISSION_COMPLETE, UUID.randomUUID(), "EARN_ACCEPTED",
                ResultClassification.NEW_SUCCESS, 10L, null, 3L, "2026-10-02", BalanceScope.creator(10L),
                "MISSION:CREATOR:DAILY:17:10:3:2026-10-02", Instant.EPOCH, Instant.EPOCH);
    }

    /** Creator Event Entry의 유효한 관찰 이벤트를 생성한다. */
    private AbuseObservationEvent entry() {
        return new AbuseObservationEvent(
                UUID.randomUUID(), 17L, AbuseActionType.EVENT_ENTRY, UUID.randomUUID(), "SUCCESS",
                ResultClassification.NEW_SUCCESS, 10L, 7L, null, null, BalanceScope.creator(10L), null,
                Instant.EPOCH, Instant.EPOCH);
    }
}
