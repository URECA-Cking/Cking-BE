package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Spring Context 없이 Refresh Token 저장 정책을 검증한다. */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Duration REFRESH_TOKEN_TTL = Duration.ofHours(12);

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private DefaultRedisScript<String> refreshTokenRotateScript;
    @Mock private ValueOperations<String, String> valueOperations;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        refreshTokenService = new RefreshTokenService(redisTemplate, refreshTokenRotateScript, REFRESH_TOKEN_TTL);
    }

    /** 생성자로 받은 TTL을 Redis 저장 만료 시간에 그대로 사용하는지 검증한다. */
    @Test
    void 생성자로_주입한_TTL로_RefreshToken을_저장한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String refreshToken = refreshTokenService.issue(17L);

        ArgumentCaptor<String> redisKey = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(redisKey.capture(), eq("17"), eq(REFRESH_TOKEN_TTL));
        assertThat(refreshToken).isNotBlank();
        assertThat(redisKey.getValue()).doesNotContain(refreshToken);
    }

    /** 회전 Lua 스크립트에도 생성자로 받은 TTL을 밀리초 단위로 전달하는지 검증한다. */
    @Test
    void 생성자로_주입한_TTL로_RefreshToken을_회전한다() {
        String ttlMillis = Long.toString(REFRESH_TOKEN_TTL.toMillis());
        when(redisTemplate.execute(eq(refreshTokenRotateScript), anyList(), eq(ttlMillis))).thenReturn("17");

        var result = refreshTokenService.rotate("old-refresh-token");

        verify(redisTemplate).execute(eq(refreshTokenRotateScript), anyList(), eq(ttlMillis));
        assertThat(result.memberId()).isEqualTo(17L);
        assertThat(result.refreshToken()).isNotBlank();
    }
}
