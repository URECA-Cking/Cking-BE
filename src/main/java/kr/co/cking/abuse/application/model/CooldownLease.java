package kr.co.cking.abuse.application.model;

import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.AbuseScopeHash;

import java.util.Objects;
import java.util.UUID;

/** 다른 요청이 재획득한 Cooldown을 이전 소유자가 삭제하지 못하게 하는 소유권 Token이다. */
public record CooldownLease(
        AbuseType abuseType,
        String scopeHash,
        UUID token
) {
    public CooldownLease {
        Objects.requireNonNull(abuseType, "abuseType은 필수입니다.");
        AbuseScopeHash.requireValidHash(scopeHash, "scopeHash");
        Objects.requireNonNull(token, "token은 필수입니다.");
    }
}
