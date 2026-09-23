package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Login Code의 원문 비저장과 1회 소비 경계를 검증한다. */
@ExtendWith(MockitoExtension.class)
class LoginCodeServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private DefaultRedisScript<String> loginCodeConsumeScript;
    @InjectMocks private LoginCodeService loginCodeService;

    /** 발급한 원문은 응답에만 남고 Redis에는 SHA-256 key와 회원 ID만 저장되는지 검증한다. */
    @Test
    void LoginCode를_해시_key와_60초_TTL로_저장한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String code = loginCodeService.issue(17L);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), eq("17"), eq(Duration.ofSeconds(60)));
        assertThat(code).hasSize(43);
        assertThat(keyCaptor.getValue())
                .startsWith("auth:login-code:")
                .doesNotContain(code)
                .matches("auth:login-code:[0-9a-f]{64}");
    }

    /** Lua 소비 결과가 있으면 해당 회원 ID를 반환하는지 검증한다. */
    @Test
    void LoginCode를_원자적으로_소비하고_회원_ID를_반환한다() {
        when(redisTemplate.execute(eq(loginCodeConsumeScript), anyList())).thenReturn("18");

        Long memberId = loginCodeService.consume("raw-login-code");

        assertThat(memberId).isEqualTo(18L);
        ArgumentCaptor<java.util.List<String>> keyCaptor = ArgumentCaptor.forClass(java.util.List.class);
        verify(redisTemplate).execute(eq(loginCodeConsumeScript), keyCaptor.capture());
        assertThat(keyCaptor.getValue().getFirst())
                .startsWith("auth:login-code:")
                .doesNotContain("raw-login-code");
    }

    /** 만료됐거나 이미 소비된 Code는 같은 Auth 오류로 통합하는지 검증한다. */
    @Test
    void 없는_LoginCode는_INVALID_LOGIN_CODE를_반환한다() {
        when(redisTemplate.execute(eq(loginCodeConsumeScript), anyList())).thenReturn(null);

        assertThatThrownBy(() -> loginCodeService.consume("expired-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_LOGIN_CODE));
    }

    /** Redis에 손상된 회원 ID가 있어도 Login Code 오류로 통합하는지 검증한다. */
    @Test
    void 손상된_회원_ID의_LoginCode는_INVALID_LOGIN_CODE를_반환한다() {
        when(redisTemplate.execute(eq(loginCodeConsumeScript), anyList())).thenReturn("not-a-member-id");

        assertInvalidLoginCode("corrupted-code");
    }

    /** null 또는 공백 Code는 Redis를 조회하지 않고 동일한 Login Code 오류로 거절하는지 검증한다. */
    @Test
    void null_또는_공백_LoginCode는_INVALID_LOGIN_CODE를_반환한다() {
        assertInvalidLoginCode(null);
        for (String code : List.of("", "  ")) {
            assertInvalidLoginCode(code);
        }

        verifyNoInteractions(redisTemplate);
    }

    /** 내부 회원 ID가 없으면 Code를 만들기 전에 개발자 오류로 거절하는지 검증한다. */
    @Test
    void null_회원_ID로는_LoginCode를_발급할_수_없다() {
        assertThatThrownBy(() -> loginCodeService.issue(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("memberId는 필수입니다.");

        verifyNoInteractions(redisTemplate);
    }

    /** 잘못된 외부 Code가 Auth 오류로 통합되는지 공통 검증한다. */
    private void assertInvalidLoginCode(String code) {
        assertThatThrownBy(() -> loginCodeService.consume(code))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_LOGIN_CODE));
    }
}
