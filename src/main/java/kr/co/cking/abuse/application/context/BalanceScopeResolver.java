package kr.co.cking.abuse.application.context;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import java.util.Objects;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.ticket.domain.CouponType;
import org.springframework.stereotype.Component;

/** Mission 보상 경로와 Event 응모 요청이 사용하는 응모권 잔액 범위를 파생한다. */
@Component
public class BalanceScopeResolver {

    public BalanceScope forCreatorMission(Long creatorId) {
        return BalanceScope.creator(creatorId);
    }

    public BalanceScope forCommonMission() {
        return BalanceScope.common();
    }

    public BalanceScope forEntry(CouponType couponType, Long eventCreatorId) {
        Objects.requireNonNull(couponType, "couponType은 필수입니다.");
        requirePositive(eventCreatorId, "eventCreatorId");
        return switch (couponType) {
            case CREATOR -> BalanceScope.creator(eventCreatorId);
            case COMMON -> BalanceScope.common();
        };
    }
}
