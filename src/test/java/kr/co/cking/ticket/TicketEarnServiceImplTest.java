package kr.co.cking.ticket;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 로컬 Redis에 붙어서 Lua 스크립트까지 검증한다(EventCacheTest와 동일하게
 * Spring 컨텍스트 없이 직접 연결 — 이 리포의 @SpringBootTest는 로컬 MySQL/Flyway
 * 상태에 따라 실패할 수 있어 그 경로를 타지 않는다). 이 테스트가 만든 키만
 * 지우도록 전용 prefix를 쓴다.
 */
class TicketEarnServiceImplTest {

    private static final String TEST_STREAM_KEY = "stream:ticket-earned:test";
    private static final Long CREATOR_ID = 900001L;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private TicketEarnServiceImpl service;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/ticket-earn.lua"));
        script.setResultType(List.class);

        service = new TicketEarnServiceImpl(redisTemplate, script, TEST_STREAM_KEY);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete("ticket:balance:%d:%d".formatted(CREATOR_ID, 1L));
        redisTemplate.delete(TEST_STREAM_KEY);
        connectionFactory.destroy();
    }

    @Test
    void 잔액을_늘리고_스트림에_발행한다() {
        EarnCommand command = new EarnCommand(UUID.randomUUID(), 1L, CREATOR_ID,
                "ATTENDANCE", 10L, "2026-09-16", "attendance:creator:2026-09-16", 1L);

        EarnResult result = service.earn(command);

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        String balance = redisTemplate.opsForValue().get("ticket:balance:%d:%d".formatted(CREATOR_ID, 1L));
        assertThat(balance).isEqualTo("1");
        assertThat(redisTemplate.opsForStream().size(TEST_STREAM_KEY)).isEqualTo(1L);
    }

    @Test
    void 여러번_적립하면_잔액이_누적된다() {
        EarnCommand command = new EarnCommand(UUID.randomUUID(), 1L, CREATOR_ID,
                "ATTENDANCE", 10L, "2026-09-16", "attendance:creator:2026-09-16", 1L);

        service.earn(command);
        service.earn(command);

        String balance = redisTemplate.opsForValue().get("ticket:balance:%d:%d".formatted(CREATOR_ID, 1L));
        assertThat(balance).isEqualTo("2");
    }
}
