package kr.co.cking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

@SpringBootTest
class RedisConnectionPoolConfigurationTest {

    @Autowired
    private LettuceConnectionFactory connectionFactory;

    /** #698: 풀이 없으면 Stream 소비자의 XREADGROUP BLOCK이 읽을 때마다 Redis 연결을 새로 연다. */
    @Test
    void BLOCK_읽기와_파이프라인은_대기_시간_상한이_있는_풀의_연결을_다시_쓴다() {
        assertThat(connectionFactory.getClientConfiguration()).isInstanceOf(LettucePoolingClientConfiguration.class);
        GenericObjectPoolConfig<?> pool =
                ((LettucePoolingClientConfiguration) connectionFactory.getClientConfiguration()).getPoolConfig();

        // 돌려받은 연결을 닫지 않고 다시 쓰려면 쉬는 연결 상한이 전체 상한과 같아야 한다.
        assertThat(pool.getMaxIdle()).isEqualTo(pool.getMaxTotal());
        assertThat(pool.getMaxWaitDuration()).isPositive();
    }
}
