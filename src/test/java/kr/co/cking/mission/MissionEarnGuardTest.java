package kr.co.cking.mission;

import kr.co.cking.mission.application.MissionEarnGuard;
import kr.co.cking.mission.domain.MissionType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MissionEarnGuardTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final MissionEarnGuard guard = new MissionEarnGuard(redisTemplate);

    @Test
    void 최초_요청이면_true를_반환하고_TTL_25시간으로_SETNX한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("mission:earn-guard:1:ATTENDANCE:10:20260916"), eq("1"), eq(Duration.ofHours(25))))
                .thenReturn(true);

        boolean result = guard.tryAcquire(1L, MissionType.ATTENDANCE, 10L, LocalDate.of(2026, 9, 16));

        assertThat(result).isTrue();
    }

    @Test
    void 이미_점유된_키면_false를_반환한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);

        boolean result = guard.tryAcquire(1L, MissionType.ATTENDANCE, 10L, LocalDate.of(2026, 9, 16));

        assertThat(result).isFalse();
    }

    @Test
    void Redis_오류가_나면_판정에_영향을_주지_않도록_true를_반환한다() {
        when(redisTemplate.opsForValue()).thenThrow(new QueryTimeoutException("redis timeout"));

        boolean result = guard.tryAcquire(1L, MissionType.ATTENDANCE, 10L, LocalDate.of(2026, 9, 16));

        assertThat(result).isTrue();
    }
}
