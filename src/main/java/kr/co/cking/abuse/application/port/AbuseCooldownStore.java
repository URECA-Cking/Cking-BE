package kr.co.cking.abuse.application.port;

import kr.co.cking.abuse.application.model.CooldownLease;
import kr.co.cking.abuse.domain.AbuseType;

import java.time.Duration;
import java.util.Optional;

/** 같은 Scope의 Detection 중복 생성을 제한하는 소유권 기반 Cooldown Port다. */
public interface AbuseCooldownStore {

    /** 점유 성공 시 소유 Token을, 이미 점유 중이면 빈 결과를 반환한다. */
    Optional<CooldownLease> tryAcquire(AbuseType abuseType, String scopeHash, Duration ttl);

    /** 저장된 소유 Token이 일치할 때만 해제하고 실제 삭제 여부를 반환한다. */
    boolean release(CooldownLease lease);
}
