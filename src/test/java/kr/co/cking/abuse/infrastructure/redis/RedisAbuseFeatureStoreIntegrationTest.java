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

    /** 같은 사용자·Event의 Entry 요청은 성공 여부와 무관하게 request window count를 누적하는지 검증한다. */
    @Test
    void Entry_요청은_사용자와_Event_범위에서_Request_Count를_누적한다() {
        AbuseFeatureSnapshot first = record(entry("SUCCESS", ResultClassification.NEW_SUCCESS, BASE_TIME));
        AbuseFeatureSnapshot second = record(entry(
                "EVENT_CLOSED", ResultClassification.BUSINESS_FAILURE, BASE_TIME.plusSeconds(1)));

        assertThat(first.valueOf(AbuseMetric.ENTRY_REQUEST_COUNT)).isEqualTo(1L);
        assertThat(second.valueOf(AbuseMetric.ENTRY_REQUEST_COUNT)).isEqualTo(2L);
    }

    /** Entry는 Mission 요청을 추가하지 않고 Redis 실행 시각의 기존 Mission window count만 Snapshot에 담는다. */
    @Test
    void Entry는_기존_Mission_Burst를_조회만_하고_요청_수를_늘리지_않는다() {
        record(mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS, BASE_TIME));
        Long missionRequestCountBeforeEntry = redisTemplate.opsForZSet()
                .zCard(AbuseRedisKeys.missionRequest(USER_ID));

        AbuseFeatureSnapshot entry = record(entry(
                "SUCCESS", ResultClassification.NEW_SUCCESS, BASE_TIME.plusSeconds(1)));

        assertThat(entry.valueOf(AbuseMetric.MISSION_REQUEST_COUNT)).isEqualTo(1L);
        assertThat(redisTemplate.opsForZSet().zCard(AbuseRedisKeys.missionRequest(USER_ID)))
                .isEqualTo(missionRequestCountBeforeEntry);
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
        assertThat(spend.valueOf(AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED)).isEqualTo(1L);
        assertThat(nextSpend.valueOf(AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT)).isZero();
        assertThat(nextSpend.valueOf(AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED)).isZero();
        assertThat(insufficientAfterSuccess.valueOf(AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT))
                .isEqualTo(1L);
        assertThat(insufficientAfterSuccess.valueOf(AbuseMetric.FAILURE_CONSECUTIVE_COUNT)).isEqualTo(1L);
    }

    /** 다른 Balance Scope의 EARN은 현재 scope의 insufficient sequence를 초기화하지 않는지 검증한다. */
    @Test
    void Balance_Scope별_Insufficient_Sequence를_독립적으로_초기화한다() {
        BalanceScope creatorScope = BalanceScope.creator(CREATOR_ID);
        BalanceScope commonScope = BalanceScope.common();
        record(entry("INSUFFICIENT_BALANCE", ResultClassification.BUSINESS_FAILURE, BASE_TIME, creatorScope));
        record(entry("INSUFFICIENT_BALANCE", ResultClassification.BUSINESS_FAILURE,
                BASE_TIME.plusSeconds(1), commonScope));
        record(commonMission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS, BASE_TIME.plusSeconds(2)));
        AbuseFeatureSnapshot creatorAfterCommonEarn = record(entry(
                "INSUFFICIENT_BALANCE", ResultClassification.BUSINESS_FAILURE,
                BASE_TIME.plusSeconds(3), creatorScope));
        AbuseFeatureSnapshot commonAfterCommonEarn = record(entry(
                "INSUFFICIENT_BALANCE", ResultClassification.BUSINESS_FAILURE,
                BASE_TIME.plusSeconds(4), commonScope));

        assertThat(creatorAfterCommonEarn.valueOf(AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT))
                .isEqualTo(2L);
        assertThat(commonAfterCommonEarn.valueOf(AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT))
                .isEqualTo(1L);
    }

    /** 두 번째 rapid pair도 누적 수와 이번 Entry의 pair 생성 여부를 함께 반환하는지 검증한다. */
    @Test
    void 두번째_Rapid_Earn_Spend_Pair의_누적과_생성_여부를_Snapshot으로_반환한다() {
        record(mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS, BASE_TIME));
        record(entry("SUCCESS", ResultClassification.NEW_SUCCESS, BASE_TIME.plusSeconds(1)));
        record(mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS, BASE_TIME.plusSeconds(2)));

        AbuseFeatureSnapshot secondPair = record(entry(
                "SUCCESS", ResultClassification.NEW_SUCCESS, BASE_TIME.plusSeconds(3)));

        assertThat(secondPair.valueOf(AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT)).isEqualTo(2L);
        assertThat(secondPair.valueOf(AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED)).isEqualTo(1L);
    }

    /** max delay가 지난 EARN은 이후 성공 Entry와 rapid pair를 만들지 않는지 검증한다. */
    @Test
    void Rapid_Earn_Spend_Max_Delay를_초과하면_Pair를_만들지_않는다() throws InterruptedException {
        AbuseFeatureWindowPolicy shortRapidDelayPolicy = new AbuseFeatureWindowPolicy(
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofMillis(1), Duration.ofSeconds(10));
        record(mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS, BASE_TIME), shortRapidDelayPolicy);
        waitForRedisExpiration();
        AbuseFeatureSnapshot delayedSpend = record(entry(
                "SUCCESS", ResultClassification.NEW_SUCCESS, BASE_TIME.plusSeconds(1)), shortRapidDelayPolicy);

        assertThat(delayedSpend.valueOf(AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT)).isZero();
        assertThat(delayedSpend.valueOf(AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED)).isZero();
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
    void Failure_Window_밖_유형은_Distinct_Count에서_제외한다() throws InterruptedException {
        AbuseFeatureWindowPolicy shortFailureWindowPolicy = new AbuseFeatureWindowPolicy(
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(5), Duration.ofMillis(1));
        record(entry("EVENT_CLOSED", ResultClassification.BUSINESS_FAILURE, BASE_TIME), shortFailureWindowPolicy);
        waitForRedisExpiration();
        AbuseFeatureSnapshot outsideWindow = record(entry(
                "EVENT_NOT_OPEN", ResultClassification.BUSINESS_FAILURE, BASE_TIME.plusSeconds(10)),
                shortFailureWindowPolicy);

        assertThat(outsideWindow.valueOf(AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT)).isEqualTo(1L);
        assertThat(outsideWindow.valueOf(AbuseMetric.FAILURE_COUNT)).isEqualTo(1L);
    }

    /** 지정한 Observation을 공통 Window 정책으로 Feature Store에 기록한다. */
    private AbuseFeatureSnapshot record(AbuseObservationEvent observation) {
        return featureStore.record(observation, WINDOW_POLICY);
    }

    /** 지정한 Window 정책으로 Observation을 기록해 짧은 window 경계 테스트를 수행한다. */
    private AbuseFeatureSnapshot record(
            AbuseObservationEvent observation, AbuseFeatureWindowPolicy windowPolicy
    ) {
        return featureStore.record(observation, windowPolicy);
    }

    /** Redis TIME 기준의 짧은 TTL 또는 window가 지나도록 대기한다. */
    private void waitForRedisExpiration() throws InterruptedException {
        Thread.sleep(20L);
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

    /** 공용 Balance Scope의 유효한 Mission 관찰 이벤트를 생성한다. */
    private AbuseObservationEvent commonMission(
            String resultCode, ResultClassification classification, Instant observedAt
    ) {
        return new AbuseObservationEvent(
                UUID.randomUUID(), USER_ID, AbuseActionType.MISSION_COMPLETE, UUID.randomUUID(), resultCode,
                classification, null, null, MISSION_ID, "2026-10-02", BalanceScope.common(),
                commonBusinessKey(), observedAt, observedAt);
    }

    /** Creator Event Entry의 유효한 관찰 이벤트를 생성한다. */
    private AbuseObservationEvent entry(String resultCode, ResultClassification classification, Instant observedAt) {
        return entry(resultCode, classification, observedAt, BalanceScope.creator(CREATOR_ID));
    }

    /** 지정한 Balance Scope를 사용하는 Creator Event Entry 관찰 이벤트를 생성한다. */
    private AbuseObservationEvent entry(
            String resultCode, ResultClassification classification, Instant observedAt, BalanceScope balanceScope
    ) {
        return new AbuseObservationEvent(
                UUID.randomUUID(), USER_ID, AbuseActionType.EVENT_ENTRY, UUID.randomUUID(), resultCode,
                classification, CREATOR_ID, EVENT_ID, null, null, balanceScope, null,
                observedAt, observedAt);
    }

    /** Mission별 requestId rotation·duplicate window에 사용할 원문 business key를 반환한다. */
    private String businessKey() {
        return "MISSION:CREATOR:DAILY:" + USER_ID + ":" + CREATOR_ID + ":" + MISSION_ID + ":2026-10-02";
    }

    /** 공용 Mission의 requestId rotation·duplicate window에 사용할 원문 business key를 반환한다. */
    private String commonBusinessKey() {
        return "MISSION:COMMON:DAILY:" + USER_ID + ":" + MISSION_ID + ":2026-10-02";
    }

    /** 테스트가 생성할 수 있는 모든 사용자·business key Redis key를 모아 반환한다. */
    private Set<String> allKeys() {
        Set<String> keys = new HashSet<>();
        BalanceScope balanceScope = BalanceScope.creator(CREATOR_ID);
        BalanceScope commonBalanceScope = BalanceScope.common();
        keys.add(AbuseRedisKeys.missionRequest(USER_ID));
        keys.add(AbuseRedisKeys.duplicateMission(businessKey()));
        keys.add(AbuseRedisKeys.duplicateMission(commonBusinessKey()));
        keys.add(AbuseRedisKeys.entryRequest(USER_ID, EVENT_ID));
        keys.add(AbuseRedisKeys.insufficientBalance(USER_ID, balanceScope));
        keys.add(AbuseRedisKeys.insufficientBalance(USER_ID, commonBalanceScope));
        keys.add(AbuseRedisKeys.requestIdRotation(businessKey()));
        keys.add(AbuseRedisKeys.requestIdRotation(commonBusinessKey()));
        keys.add(AbuseRedisKeys.lastEarn(USER_ID, balanceScope));
        keys.add(AbuseRedisKeys.lastEarn(USER_ID, commonBalanceScope));
        keys.add(AbuseRedisKeys.rapidEarnSpend(USER_ID, balanceScope));
        keys.add(AbuseRedisKeys.rapidEarnSpend(USER_ID, commonBalanceScope));
        keys.add(AbuseRedisKeys.failure(USER_ID));
        keys.add(AbuseRedisKeys.failureType(USER_ID));
        keys.add(AbuseRedisKeys.failureSequence(USER_ID));
        keys.add(AbuseRedisKeys.insufficientBalanceSequence(USER_ID, balanceScope));
        keys.add(AbuseRedisKeys.insufficientBalanceSequence(USER_ID, commonBalanceScope));
        return keys;
    }
}
