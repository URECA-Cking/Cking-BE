package kr.co.cking.event.application.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class EntryLuaConfig {

    @Bean
    public DefaultRedisScript<List> entrySpendLuaScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();

        script.setLocation(new ClassPathResource("scripts/entry-spend.lua"));
        script.setResultType(List.class);

        return script;
    }

    @Bean
    public DefaultRedisScript<String> eventCloseBarrierLuaScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();

        script.setLocation(new ClassPathResource("scripts/event-close-barrier.lua"));
        script.setResultType(String.class);

        return script;
    }

    @Bean
    public DefaultRedisScript<Long> eventGateLoadLuaScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();

        script.setLocation(new ClassPathResource("scripts/event-gate-load.lua"));
        script.setResultType(Long.class);

        return script;
    }
}
