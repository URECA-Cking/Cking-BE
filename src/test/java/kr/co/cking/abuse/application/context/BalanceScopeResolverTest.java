package kr.co.cking.abuse.application.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.ticket.domain.CouponType;
import org.junit.jupiter.api.Test;

class BalanceScopeResolverTest {

    private final BalanceScopeResolver resolver = new BalanceScopeResolver();

    @Test
    void Creator_미션은_해당_Creator의_전용_잔액을_사용한다() {
        assertThat(resolver.forCreatorMission(5L)).isEqualTo(BalanceScope.creator(5L));
    }

    @Test
    void 공용_미션은_공용_잔액을_사용한다() {
        assertThat(resolver.forCommonMission()).isEqualTo(BalanceScope.common());
    }

    @Test
    void 같은_Event라도_응모권_종류에_따라_잔액_범위가_다르다() {
        assertThat(resolver.forEntry(CouponType.CREATOR, 5L))
                .isEqualTo(BalanceScope.creator(5L));
        assertThat(resolver.forEntry(CouponType.COMMON, 5L))
                .isEqualTo(BalanceScope.common());
    }

    @Test
    void Event_Creator와_응모권_종류가_없거나_유효하지_않으면_거부한다() {
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.forCreatorMission(0L));
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.forEntry(CouponType.COMMON, -1L));
        assertThatNullPointerException().isThrownBy(() -> resolver.forEntry(null, 5L));
    }
}
