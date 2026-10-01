package kr.co.cking.abuse.application.model;

import kr.co.cking.abuse.domain.AbuseType;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** 다른 요청이 재획득한 Cooldown을 이전 소유자가 삭제하지 못하게 하는 소유권 Token이다. */
public record CooldownLease(
        AbuseType abuseType,
        String scopeHash,
        UUID token
) {
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public CooldownLease {
        Objects.requireNonNull(abuseType, "abuseType은 필수입니다.");
        if (scopeHash == null || !SHA256.matcher(scopeHash).matches()) {
            throw new IllegalArgumentException("scopeHash는 lowercase SHA-256 hex여야 합니다.");
        }
        Objects.requireNonNull(token, "token은 필수입니다.");
    }
}
