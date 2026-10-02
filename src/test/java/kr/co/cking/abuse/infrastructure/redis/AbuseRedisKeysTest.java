package kr.co.cking.abuse.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.BalanceScope;
import org.junit.jupiter.api.Test;

/** Abuse Detection v1 Redis key의 prefix, hash, scope 표현 계약을 검증한다. */
class AbuseRedisKeysTest {

    /** 사용자·Event 범위 feature key가 정해진 prefix와 식별자 segment를 사용한다. */
    @Test
    void 사용자와_Event_범위_feature_key를_생성한다() {
        assertThat(AbuseRedisKeys.missionRequest(7L))
                .isEqualTo("abuse:v1:mission-request:7");
        assertThat(AbuseRedisKeys.entryRequest(7L, 11L))
                .isEqualTo("abuse:v1:entry-request:7:11");
        assertThat(AbuseRedisKeys.failure(7L))
                .isEqualTo("abuse:v1:failure:7");
        assertThat(AbuseRedisKeys.failureType(7L))
                .isEqualTo("abuse:v1:failure-type:7");
        assertThat(AbuseRedisKeys.failureSequence(7L))
                .isEqualTo("abuse:v1:failure-sequence:7");
    }

    /** Mission Business Key 원문 대신 SHA-256 lowercase hex만 Redis key에 포함한다. */
    @Test
    void Mission_Business_Key를_hash로_치환한다() {
        String businessKey = "MISSION:CREATOR:DAILY:7:10:3:2026-10-01";
        String hash = AbuseScopeHash.fromCanonicalValue(businessKey);

        String duplicateMissionKey = AbuseRedisKeys.duplicateMission(businessKey);
        String requestIdRotationKey = AbuseRedisKeys.requestIdRotation(businessKey);

        assertThat(duplicateMissionKey)
                .isEqualTo("abuse:v1:duplicate-mission:" + hash)
                .doesNotContain(businessKey);
        assertThat(requestIdRotationKey)
                .isEqualTo("abuse:v1:request-id:" + hash)
                .doesNotContain(businessKey);
    }

    /** 공용과 Creator 전용 Balance Scope가 canonical 표현으로 모든 관련 key에 포함된다. */
    @Test
    void canonical_Balance_Scope를_관련_feature_key에_적용한다() {
        assertThat(AbuseRedisKeys.insufficientBalance(7L, BalanceScope.common()))
                .isEqualTo("abuse:v1:insufficient:7:COMMON");
        assertThat(AbuseRedisKeys.lastEarn(7L, BalanceScope.creator(10L)))
                .isEqualTo("abuse:v1:last-earn:7:CREATOR:10");
        assertThat(AbuseRedisKeys.rapidEarnSpend(7L, BalanceScope.creator(10L)))
                .isEqualTo("abuse:v1:rapid-earn-spend:7:CREATOR:10");
        assertThat(AbuseRedisKeys.insufficientBalanceSequence(7L, BalanceScope.common()))
                .isEqualTo("abuse:v1:insufficient-sequence:7:COMMON");
        assertThat(AbuseRedisKeys.insufficientBalanceSequence(7L, BalanceScope.creator(10L)))
                .isEqualTo("abuse:v1:insufficient-sequence:7:CREATOR:10");
    }

    /** cooldown key는 탐지 유형과 검증된 scope hash를 정해진 순서로 조합한다. */
    @Test
    void cooldown_key를_생성한다() {
        String scopeHash = AbuseScopeHash.fromCanonicalValue("USER:7");

        assertThat(AbuseRedisKeys.cooldown(AbuseType.MISSION_REQUEST_BURST, scopeHash))
                .isEqualTo("abuse:v1:cooldown:MISSION_REQUEST_BURST:" + scopeHash);
    }

    /** 필수 식별자와 hash 형식이 잘못되면 잘못된 Redis key 생성을 거부한다. */
    @Test
    void 잘못된_key_입력을_거부한다() {
        assertThatThrownBy(() -> AbuseRedisKeys.missionRequest(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbuseRedisKeys.entryRequest(7L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbuseRedisKeys.insufficientBalance(7L, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AbuseRedisKeys.insufficientBalanceSequence(0L, BalanceScope.common()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbuseRedisKeys.duplicateMission(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbuseRedisKeys.cooldown(AbuseType.FAILURE_BURST, "not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
