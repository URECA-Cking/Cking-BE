package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

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
}
