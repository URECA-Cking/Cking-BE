package kr.co.cking.ticket.application.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class QuizTicketEarnLuaConfig {

    @Bean
    public DefaultRedisScript<List> quizTicketEarnLuaScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();

        script.setLocation(new ClassPathResource("scripts/quiz-ticket-earn.lua"));
        script.setResultType(List.class);

        return script;
    }
}
