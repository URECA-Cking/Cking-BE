package kr.co.cking.abuse.infrastructure.redis;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import java.util.Objects;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.BalanceScope;

/** Abuse Detection v1의 실시간 feature와 cooldown Redis key를 일관되게 생성한다. */
public final class AbuseRedisKeys {

    private static final String PREFIX = "abuse:v1:";

    /** 정적 Redis key 유틸리티의 인스턴스 생성을 막는다. */
    private AbuseRedisKeys() {
    }

    /** 사용자별 Mission 완료 요청 sliding window key를 생성한다. */
    public static String missionRequest(Long userId) {
        return PREFIX + "mission-request:" + positiveId(userId, "userId");
    }

    /** Mission Business Key 원문을 hash로 치환한 중복 Mission sliding window key를 생성한다. */
    public static String duplicateMission(String businessKey) {
        return PREFIX + "duplicate-mission:" + businessKeyHash(businessKey);
    }

    /** 사용자와 Event 조합의 응모 요청 sliding window key를 생성한다. */
    public static String entryRequest(Long userId, Long eventId) {
        return PREFIX + "entry-request:" + positiveId(userId, "userId") + ":"
                + positiveId(eventId, "eventId");
    }

    /** 사용자 잔액 범위의 부족 잔액 sliding window key를 생성한다. */
    public static String insufficientBalance(Long userId, BalanceScope balanceScope) {
        return balanceScopeKey("insufficient", userId, balanceScope);
    }

    /** Mission Business Key 원문을 hash로 치환한 requestId rotation key를 생성한다. */
    public static String requestIdRotation(String businessKey) {
        return PREFIX + "request-id:" + businessKeyHash(businessKey);
    }

    /** 사용자 잔액 범위의 최근 EARN 시각 key를 생성한다. */
    public static String lastEarn(Long userId, BalanceScope balanceScope) {
        return balanceScopeKey("last-earn", userId, balanceScope);
    }

    /** 사용자 잔액 범위의 빠른 EARN-SPEND pair sliding window key를 생성한다. */
    public static String rapidEarnSpend(Long userId, BalanceScope balanceScope) {
        return balanceScopeKey("rapid-earn-spend", userId, balanceScope);
    }

    /** 사용자별 업무 실패 sliding window key를 생성한다. */
    public static String failure(Long userId) {
        return PREFIX + "failure:" + positiveId(userId, "userId");
    }

    /** 사용자별 서로 다른 업무 실패 유형 집계 key를 생성한다. */
    public static String failureType(Long userId) {
        return PREFIX + "failure-type:" + positiveId(userId, "userId");
    }

    /** 사용자별 전체 업무 실패 연속 판정 상태 key를 생성한다. */
    public static String failureSequence(Long userId) {
        return PREFIX + "failure-sequence:" + positiveId(userId, "userId");
    }

    /** 사용자와 잔액 범위별 부족 잔액 연속 판정 상태 key를 생성한다. */
    public static String insufficientBalanceSequence(Long userId, BalanceScope balanceScope) {
        return balanceScopeKey("insufficient-sequence", userId, balanceScope);
    }

    /** 탐지 유형과 이미 hash 처리된 scope의 중복 생성 방지 cooldown key를 생성한다. */
    public static String cooldown(AbuseType abuseType, String scopeHash) {
        Objects.requireNonNull(abuseType, "abuseType은 필수입니다.");
        AbuseScopeHash.requireValidHash(scopeHash, "scopeHash");
        return PREFIX + "cooldown:" + abuseType.name() + ":" + scopeHash;
    }

    /** Mission Business Key 원문을 Redis 노출 없는 SHA-256 hash로 변환한다. */
    private static String businessKeyHash(String businessKey) {
        return AbuseScopeHash.fromCanonicalValue(businessKey);
    }

    /** 사용자와 canonical Balance Scope를 조합하는 feature key를 생성한다. */
    private static String balanceScopeKey(String featureName, Long userId, BalanceScope balanceScope) {
        Objects.requireNonNull(balanceScope, "balanceScope는 필수입니다.");
        return PREFIX + featureName + ":" + positiveId(userId, "userId") + ":"
                + balanceScope.canonicalValue();
    }

    /** key segment로 사용할 식별자가 양수인지 검증한 뒤 문자열로 변환한다. */
    private static String positiveId(Long value, String name) {
        requirePositive(value, name);
        return String.valueOf(value);
    }
}
