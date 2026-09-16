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
}
