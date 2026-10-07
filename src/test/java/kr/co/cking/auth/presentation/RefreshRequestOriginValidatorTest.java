package kr.co.cking.auth.presentation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.auth.domain.RefreshSessionType;
import org.junit.jupiter.api.Test;

/** Refresh·Logout 요청의 Origin 검증 규칙을 확인한다. */
class RefreshRequestOriginValidatorTest {

    /** 사용자·관리자 Web 세션이 각자 설정된 Origin에서만 Cookie 기반 인증 요청을 수행하는지 검증한다. */
    @Test
    void 허용된_Origin은_통과한다() {
        RefreshRequestOriginValidator validator = new RefreshRequestOriginValidator(
                new String[]{"https://dev.cking.co.kr"}, new String[]{"https://dev-admin.cking.co.kr"});

        assertThatCode(() -> validator.validate("https://dev.cking.co.kr", RefreshSessionType.USER_WEB))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("https://dev-admin.cking.co.kr", RefreshSessionType.ADMIN_WEB))
                .doesNotThrowAnyException();
    }

    /** 누락되거나 다른 origin은 CSRF 방어를 위해 거절하는지 검증한다. */
    @Test
    void 허용되지_않은_Origin은_거절한다() {
        RefreshRequestOriginValidator validator = new RefreshRequestOriginValidator(
                new String[]{"https://dev.cking.co.kr"}, new String[]{"https://dev-admin.cking.co.kr"});

        assertThatThrownBy(() -> validator.validate("https://dev-admin.cking.co.kr", RefreshSessionType.USER_WEB))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> validator.validate("https://unknown.cking.co.kr", RefreshSessionType.ADMIN_WEB))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> validator.validate(null, RefreshSessionType.USER_WEB))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.FORBIDDEN));
    }
}
