package kr.co.cking.abuse.infrastructure.redis;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.application.model.AbuseFeatureWindowPolicy;
import kr.co.cking.abuse.application.port.AbuseFeatureStore;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis Lua로 Observation의 모든 실시간 Feature를 원자 갱신하는 AbuseFeatureStore 구현체다. */
@Component
public class RedisAbuseFeatureStore implements AbuseFeatureStore {

    private static final String UNUSED_KEY = "abuse:v1:feature-store:unused";
    private static final List<AbuseMetric> SNAPSHOT_METRICS = List.of(
            AbuseMetric.MISSION_REQUEST_COUNT,
            AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT,
            AbuseMetric.ENTRY_REQUEST_COUNT,
            AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT,
            AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT,
            AbuseMetric.DISTINCT_REQUEST_ID_COUNT,
            AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT,
            AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED,
            AbuseMetric.FAILURE_COUNT,
            AbuseMetric.FAILURE_CONSECUTIVE_COUNT,
            AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT);

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> featureStoreRecordLuaScript;

    /** Redis 실행기와 Feature 일괄 갱신 Lua 스크립트를 주입받는다. */
    public RedisAbuseFeatureStore(
            StringRedisTemplate redisTemplate,
            @Qualifier("abuseFeatureStoreRecordLuaScript") DefaultRedisScript<List> featureStoreRecordLuaScript
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate은 필수입니다.");
        this.featureStoreRecordLuaScript = Objects.requireNonNull(
                featureStoreRecordLuaScript, "featureStoreRecordLuaScript는 필수입니다.");
    }

    /** 결과 분류에 맞는 모든 Feature를 원자 갱신하고 Rule Engine용 Snapshot으로 변환한다. */
    @Override
    public AbuseFeatureSnapshot record(
            AbuseObservationEvent observation, AbuseFeatureWindowPolicy windowPolicy
    ) {
        Objects.requireNonNull(observation, "observation은 필수입니다.");
        Objects.requireNonNull(windowPolicy, "windowPolicy는 필수입니다.");

        List<?> result = redisTemplate.execute(
                featureStoreRecordLuaScript,
                keysFor(observation),
                observation.actionType().name(),
                observation.resultClassification().name(),
                observation.resultCode(),
                observation.observationId().toString(),
                observation.requestId().toString(),
                milliseconds(windowPolicy.missionRequestWindow()),
                milliseconds(windowPolicy.duplicateMissionWindow()),
                milliseconds(windowPolicy.entryRequestWindow()),
                milliseconds(windowPolicy.insufficientBalanceWindow()),
                milliseconds(windowPolicy.requestIdRotationWindow()),
                milliseconds(windowPolicy.rapidEarnSpendWindow()),
                milliseconds(windowPolicy.rapidEarnSpendMaxDelay()),
                milliseconds(windowPolicy.failureWindow()));
        return toSnapshot(result);
    }

    /** Lua의 고정 KEYS 순서에 맞춰 관찰 범위별 Redis key를 준비한다. */
    private List<String> keysFor(AbuseObservationEvent observation) {
        return List.of(
                AbuseRedisKeys.missionRequest(observation.userId()),
                duplicateMissionKey(observation),
                observation.eventId() == null
                        ? UNUSED_KEY : AbuseRedisKeys.entryRequest(observation.userId(), observation.eventId()),
                AbuseRedisKeys.insufficientBalance(observation.userId(), observation.balanceScope()),
                requestIdRotationKey(observation),
                AbuseRedisKeys.lastEarn(observation.userId(), observation.balanceScope()),
                AbuseRedisKeys.rapidEarnSpend(observation.userId(), observation.balanceScope()),
                AbuseRedisKeys.failure(observation.userId()),
                AbuseRedisKeys.failureType(observation.userId()),
                AbuseRedisKeys.failureSequence(observation.userId()),
                AbuseRedisKeys.insufficientBalanceSequence(observation.userId(), observation.balanceScope()));
    }

    /** Mission business key가 있을 때만 중복 Mission window key를 hash로 생성한다. */
    private String duplicateMissionKey(AbuseObservationEvent observation) {
        return observation.businessKey() == null
                ? UNUSED_KEY : AbuseRedisKeys.duplicateMission(observation.businessKey());
    }

    /** Mission business key가 있을 때만 requestId rotation window key를 hash로 생성한다. */
    private String requestIdRotationKey(AbuseObservationEvent observation) {
        return observation.businessKey() == null
                ? UNUSED_KEY : AbuseRedisKeys.requestIdRotation(observation.businessKey());
    }

    /** Lua가 반환한 metric 순서와 값 개수를 검증해 불변 Snapshot으로 바꾼다. */
    private AbuseFeatureSnapshot toSnapshot(List<?> result) {
        if (result == null || result.size() != SNAPSHOT_METRICS.size()) {
            throw new IllegalStateException("Abuse Feature Lua가 완전한 Snapshot을 반환하지 않았습니다.");
        }
        EnumMap<AbuseMetric, Long> values = new EnumMap<>(AbuseMetric.class);
        for (int index = 0; index < SNAPSHOT_METRICS.size(); index++) {
            values.put(SNAPSHOT_METRICS.get(index), toLong(result.get(index)));
        }
        return new AbuseFeatureSnapshot(values);
    }

    /** Redis Lua의 정수 응답을 0 이상 long 값으로 검증해 변환한다. */
    private long toLong(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw new IllegalStateException("Abuse Feature Lua가 유효하지 않은 metric 값을 반환했습니다.");
        }
        return number.longValue();
    }

    /** Duration을 Lua가 사용하는 양의 밀리초 문자열로 변환한다. */
    private String milliseconds(Duration duration) {
        long milliseconds = duration.toMillis();
        if (milliseconds <= 0) {
            throw new IllegalArgumentException("Feature window는 최소 1밀리초 이상이어야 합니다.");
        }
        return String.valueOf(milliseconds);
    }
}
