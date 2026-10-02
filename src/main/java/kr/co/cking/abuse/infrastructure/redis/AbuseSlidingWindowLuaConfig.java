package kr.co.cking.abuse.infrastructure.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Abuse Detection Sliding Window의 원자적 count Lua 스크립트를 등록한다. */
@Configuration
public class AbuseSlidingWindowLuaConfig {

    /** ZSET 갱신·만료 데이터 제거·count 조회를 한 번에 수행하는 Lua 스크립트를 제공한다. */
    @Bean
    public DefaultRedisScript<Long> abuseSlidingWindowCountLuaScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/abuse-sliding-window-count.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
