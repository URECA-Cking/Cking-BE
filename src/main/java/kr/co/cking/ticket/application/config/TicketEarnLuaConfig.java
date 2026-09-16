package kr.co.cking.ticket.application.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class TicketEarnLuaConfig {

    @Bean
    public DefaultRedisScript<List> ticketEarnLuaScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();

        script.setLocation(new ClassPathResource("scripts/ticket-earn.lua"));
        script.setResultType(List.class);

        return script;
    }
}
