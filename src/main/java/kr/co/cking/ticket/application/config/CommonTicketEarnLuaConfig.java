package kr.co.cking.ticket.application.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class CommonTicketEarnLuaConfig {

    @Bean
    public DefaultRedisScript<List> commonTicketEarnLuaScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();

        script.setLocation(new ClassPathResource("scripts/common-ticket-earn.lua"));
        script.setResultType(List.class);

        return script;
    }
}
