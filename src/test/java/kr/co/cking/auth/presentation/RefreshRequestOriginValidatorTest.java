package kr.co.cking.auth.presentation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

/** Refresh·Logout 요청의 Origin 검증 규칙을 확인한다. */
class RefreshRequestOriginValidatorTest {

    /** 설정된 origin은 Cookie 기반 인증 요청을 수행할 수 있는지 검증한다. */
    @Test
    void 허용된_Origin은_통과한다() {
        RefreshRequestOriginValidator validator = new RefreshRequestOriginValidator(
                new String[]{"https://dev.cking.co.kr"});

        assertThatCode(() -> validator.validate("https://dev.cking.co.kr")).doesNotThrowAnyException();
    }

    /** 누락되거나 다른 origin은 CSRF 방어를 위해 거절하는지 검증한다. */
    @Test
    void 허용되지_않은_Origin은_거절한다() {
        RefreshRequestOriginValidator validator = new RefreshRequestOriginValidator(
                new String[]{"https://dev.cking.co.kr"});

        assertThatThrownBy(() -> validator.validate("https://attacker.example"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(BusinessException.class);
    }
}
