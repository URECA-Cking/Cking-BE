package kr.co.cking.abuse.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.application.model.AbuseFeatureWindowPolicy;
import kr.co.cking.abuse.application.port.AbuseFeatureStore;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.ResultClassification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis AbuseFeatureStore의 결과 분류별 Feature·Reset·Snapshot 계약을 통합 검증한다. */
@SpringBootTest
class RedisAbuseFeatureStoreIntegrationTest {

    private static final Long USER_ID = 9_876_543L;
    private static final Long CREATOR_ID = 765L;
    private static final Long MISSION_ID = 432L;
    private static final Long EVENT_ID = 123L;
    private static final Instant BASE_TIME = Instant.parse("2026-10-02T00:00:00Z");
    private static final AbuseFeatureWindowPolicy WINDOW_POLICY = new AbuseFeatureWindowPolicy(
            Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10),
            Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10),
            Duration.ofSeconds(5), Duration.ofSeconds(10));

    @Autowired
    private AbuseFeatureStore featureStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 테스트가 사용한 사용자와 business key 범위의 Redis key만 삭제한다. */
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(allKeys());
    }

    /** Mission 성공과 중복 실패가 요청·rotation·중복·실패 Feature를 각각 갱신하는지 검증한다. */
    @Test
    void Mission_성공과_중복_실패의_관련_Feature를_Snapshot으로_반환한다() {
        AbuseFeatureSnapshot success = record(mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS, BASE_TIME));
        AbuseFeatureSnapshot duplicate = record(mission(
                "DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE, BASE_TIME.plusSeconds(1)));

        assertThat(success.valueOf(AbuseMetric.MISSION_REQUEST_COUNT)).isEqualTo(1L);
        assertThat(success.valueOf(AbuseMetric.DISTINCT_REQUEST_ID_COUNT)).isEqualTo(1L);
        assertThat(duplicate.valueOf(AbuseMetric.MISSION_REQUEST_COUNT)).isEqualTo(2L);
        assertThat(duplicate.valueOf(AbuseMetric.DISTINCT_REQUEST_ID_COUNT)).isEqualTo(2L);
        assertThat(duplicate.valueOf(AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT)).isEqualTo(1L);
        assertThat(duplicate.valueOf(AbuseMetric.FAILURE_COUNT)).isEqualTo(1L);
        assertThat(duplicate.valueOf(AbuseMetric.FAILURE_CONSECUTIVE_COUNT)).isEqualTo(1L);
        assertThat(duplicate.valueOf(AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT)).isEqualTo(1L);
    }

    /** 같은 requestId의 재시도는 request window에는 남아도 distinct requestId count를 늘리지 않는지 검증한다. */
    @Test
    void 같은_RequestId_재시도는_Distinct_RequestId_Count를_늘리지_않는다() {
        UUID requestId = UUID.randomUUID();
        record(mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS, BASE_TIME, requestId));
        AbuseFeatureSnapshot retry = record(mission(
                "DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE, BASE_TIME.plusSeconds(1), requestId));

        assertThat(retry.valueOf(AbuseMetric.MISSION_REQUEST_COUNT)).isEqualTo(2L);
        assertThat(retry.valueOf(AbuseMetric.DISTINCT_REQUEST_ID_COUNT)).isEqualTo(1L);
    }

    /** 부족 잔액 실패는 해당 balance scope의 Window·연속 횟수와 사용자 실패 Feature를 함께 갱신한다. */
    @Test
    void 부족_잔액_실패는_범위별_연속_횟수와_실패_유형을_누적한다() {
        AbuseFeatureSnapshot first = record(entry(
                "INSUFFICIENT_BALANCE", ResultClassification.BUSINESS_FAILURE, BASE_TIME));
        AbuseFeatureSnapshot second = record(entry(
                "EVENT_CLOSED", ResultClassification.BUSINESS_FAILURE, BASE_TIME.plusSeconds(1)));
        AbuseFeatureSnapshot third = record(entry(
                "INSUFFICIENT_BALANCE", ResultClassification.BUSINESS_FAILURE, BASE_TIME.plusSeconds(2)));

        assertThat(first.valueOf(AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT)).isEqualTo(1L);
        assertThat(first.valueOf(AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT)).isEqualTo(1L);
        assertThat(second.valueOf(AbuseMetric.FAILURE_CONSECUTIVE_COUNT)).isEqualTo(2L);
        assertThat(second.valueOf(AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT)).isEqualTo(2L);
        assertThat(third.valueOf(AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT)).isEqualTo(2L);
        assertThat(third.valueOf(AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT)).isEqualTo(2L);
        assertThat(third.valueOf(AbuseMetric.FAILURE_COUNT)).isEqualTo(3L);
        assertThat(third.valueOf(AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT)).isEqualTo(2L);
    }

    /** 새 EARN과 Entry 성공이 sequence를 초기화하고 한 EARN을 한 번의 SPEND에만 연결하는지 검증한다. */
    @Test
    void 성공은_Sequence를_초기화하고_빠른_EARN_SPEND_Pair를_한번만_반영한다() {
        record(entry("INSUFFICIENT_BALANCE", ResultClassification.BUSINESS_FAILURE, BASE_TIME));
        record(mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS, BASE_TIME.plusSeconds(1)));
        AbuseFeatureSnapshot spend = record(entry("SUCCESS", ResultClassification.NEW_SUCCESS, BASE_TIME.plusSeconds(2)));
        AbuseFeatureSnapshot nextSpend = record(entry("SUCCESS", ResultClassification.NEW_SUCCESS, BASE_TIME.plusSeconds(3)));
        AbuseFeatureSnapshot insufficientAfterSuccess = record(entry(
                "INSUFFICIENT_BALANCE", ResultClassification.BUSINESS_FAILURE, BASE_TIME.plusSeconds(4)));

        assertThat(spend.valueOf(AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT)).isEqualTo(1L);
        assertThat(nextSpend.valueOf(AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT)).isZero();
        assertThat(insufficientAfterSuccess.valueOf(AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT))
                .isEqualTo(1L);
        assertThat(insufficientAfterSuccess.valueOf(AbuseMetric.FAILURE_CONSECUTIVE_COUNT)).isEqualTo(1L);
    }

    /** Replay와 system failure는 기존 Feature를 바꾸지 않고 0으로 채운 Snapshot만 반환하는지 검증한다. */
    @Test
    void Replay와_System_Failure는_어떤_Feature도_갱신하지_않는다() {
        record(mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS, BASE_TIME));
        AbuseFeatureSnapshot replay = record(mission(
                "ALREADY_PROCESSED", ResultClassification.REPLAY, BASE_TIME.plusSeconds(1)));
        AbuseFeatureSnapshot systemFailure = record(mission(
                "SYSTEM_ERROR", ResultClassification.SYSTEM_FAILURE, BASE_TIME.plusSeconds(2)));

        assertThat(replay.values()).allSatisfy((metric, value) -> assertThat(value).isZero());
        assertThat(systemFailure.values()).allSatisfy((metric, value) -> assertThat(value).isZero());
        assertThat(redisTemplate.opsForZSet().zCard(AbuseRedisKeys.missionRequest(USER_ID))).isEqualTo(1L);
        assertThat(redisTemplate.opsForZSet().zCard(AbuseRedisKeys.requestIdRotation(businessKey()))).isEqualTo(1L);
    }

    /** failure window 경계 밖의 유형은 제거해 distinct failure type count에 포함하지 않는지 검증한다. */
    @Test
    void Failure_Window_밖_유형은_Distinct_Count에서_제외한다() {
        record(entry("EVENT_CLOSED", ResultClassification.BUSINESS_FAILURE, BASE_TIME));
        AbuseFeatureSnapshot withinWindow = record(entry(
                "INSUFFICIENT_BALANCE", ResultClassification.BUSINESS_FAILURE, BASE_TIME.plusSeconds(9)));
        AbuseFeatureSnapshot outsideWindow = record(entry(
                "EVENT_NOT_OPEN", ResultClassification.BUSINESS_FAILURE, BASE_TIME.plusSeconds(10)));

        assertThat(withinWindow.valueOf(AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT)).isEqualTo(2L);
        assertThat(outsideWindow.valueOf(AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT)).isEqualTo(2L);
        assertThat(outsideWindow.valueOf(AbuseMetric.FAILURE_COUNT)).isEqualTo(2L);
    }

    /** 지정한 Observation을 공통 Window 정책으로 Feature Store에 기록한다. */
    private AbuseFeatureSnapshot record(AbuseObservationEvent observation) {
        return featureStore.record(observation, WINDOW_POLICY);
    }

    /** Creator Mission의 유효한 관찰 이벤트를 생성한다. */
    private AbuseObservationEvent mission(String resultCode, ResultClassification classification, Instant observedAt) {
        return mission(resultCode, classification, observedAt, UUID.randomUUID());
    }

    /** 지정한 requestId를 사용하는 Creator Mission 관찰 이벤트를 생성한다. */
    private AbuseObservationEvent mission(
            String resultCode, ResultClassification classification, Instant observedAt, UUID requestId
    ) {
        return new AbuseObservationEvent(
                UUID.randomUUID(), USER_ID, AbuseActionType.MISSION_COMPLETE, requestId, resultCode,
                classification, CREATOR_ID, null, MISSION_ID, "2026-10-02", BalanceScope.creator(CREATOR_ID),
                businessKey(), observedAt, observedAt);
    }

    /** Creator Event Entry의 유효한 관찰 이벤트를 생성한다. */
    private AbuseObservationEvent entry(String resultCode, ResultClassification classification, Instant observedAt) {
        return new AbuseObservationEvent(
                UUID.randomUUID(), USER_ID, AbuseActionType.EVENT_ENTRY, UUID.randomUUID(), resultCode,
                classification, CREATOR_ID, EVENT_ID, null, null, BalanceScope.creator(CREATOR_ID), null,
                observedAt, observedAt);
    }

    /** Mission별 requestId rotation·duplicate window에 사용할 원문 business key를 반환한다. */
    private String businessKey() {
        return "MISSION:CREATOR:DAILY:" + USER_ID + ":" + CREATOR_ID + ":" + MISSION_ID + ":2026-10-02";
    }

    /** 테스트가 생성할 수 있는 모든 사용자·business key Redis key를 모아 반환한다. */
    private Set<String> allKeys() {
        Set<String> keys = new HashSet<>();
        BalanceScope balanceScope = BalanceScope.creator(CREATOR_ID);
        keys.add(AbuseRedisKeys.missionRequest(USER_ID));
        keys.add(AbuseRedisKeys.duplicateMission(businessKey()));
        keys.add(AbuseRedisKeys.entryRequest(USER_ID, EVENT_ID));
        keys.add(AbuseRedisKeys.insufficientBalance(USER_ID, balanceScope));
        keys.add(AbuseRedisKeys.requestIdRotation(businessKey()));
        keys.add(AbuseRedisKeys.lastEarn(USER_ID, balanceScope));
        keys.add(AbuseRedisKeys.rapidEarnSpend(USER_ID, balanceScope));
        keys.add(AbuseRedisKeys.failure(USER_ID));
        keys.add(AbuseRedisKeys.failureType(USER_ID));
        keys.add(AbuseRedisKeys.failureSequence(USER_ID));
        keys.add(AbuseRedisKeys.insufficientBalanceSequence(USER_ID, balanceScope));
        return keys;
    }
}
