package kr.co.cking.abuse.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Sliding Window Redis Counter의 입력 계약과 Lua 비정상 반환 방어를 단위 검증한다. */
class AbuseSlidingWindowRedisCounterTest {

    private static final String KEY = "abuse:v1:test:sliding-window:validation";
    private static final String MEMBER = "observation-1";
    private static final Duration WINDOW = Duration.ofSeconds(1);

    private StringRedisTemplate redisTemplate;
    private DefaultRedisScript<Long> luaScript;
    private AbuseSlidingWindowRedisCounter counter;

    /** 각 테스트가 독립된 RedisTemplate mock과 Counter를 사용하도록 초기화한다. */
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        luaScript = new DefaultRedisScript<>();
        counter = new AbuseSlidingWindowRedisCounter(redisTemplate, luaScript);
    }

    /** 애플리케이션 observedAt 없이 member·window·TTL만 Lua에 전달해 Redis TIME을 기준으로 삼는다. */
    @Test
    void 기준_시각을_전달하지_않고_Redis_TIME에_위임한다() {
        when(redisTemplate.execute(luaScript, List.of(KEY), MEMBER, "1000", "61"))
                .thenReturn(1L);

        long count = counter.recordAndCount(KEY, MEMBER, WINDOW);

        assertThat(count).isEqualTo(1L);
        verify(redisTemplate).execute(eq(luaScript), eq(List.of(KEY)), eq(MEMBER), eq("1000"), eq("61"));
    }

    /** key가 null 또는 공백이면 Redis Lua 실행 전에 입력을 거부한다. */
    @Test
    void key가_null이거나_공백이면_거부한다() {
        assertThatThrownBy(() -> counter.recordAndCount(null, MEMBER, WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counter.recordAndCount(" ", MEMBER, WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** member가 누락되면 Redis Lua 실행 전에 입력을 거부한다. */
    @Test
    void member가_누락되면_거부한다() {
        assertThatThrownBy(() -> counter.recordAndCount(KEY, null, WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counter.recordAndCount(KEY, " ", WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 0·음수·서브밀리초 window는 Lua에 0ms로 전달되기 전에 거부한다. */
    @Test
    void 유효하지_않은_window를_거부한다() {
        assertThatThrownBy(() -> counter.recordAndCount(KEY, MEMBER, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> counter.recordAndCount(KEY, MEMBER, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counter.recordAndCount(KEY, MEMBER, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counter.recordAndCount(KEY, MEMBER, Duration.ofNanos(500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Lua가 count를 반환하지 않으면 정상 count로 해석하지 않고 즉시 실패시킨다. */
    @Test
    void Lua가_null_count를_반환하면_실패한다() {
        when(redisTemplate.execute(
                ArgumentMatchers.<DefaultRedisScript<Long>>any(),
                ArgumentMatchers.<String>anyList(),
                ArgumentMatchers.<Object[]>any()))
                .thenReturn(null);

        assertThatThrownBy(() -> counter.recordAndCount(KEY, MEMBER, WINDOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Sliding Window Lua가 count를 반환하지 않았습니다.");
    }
}
