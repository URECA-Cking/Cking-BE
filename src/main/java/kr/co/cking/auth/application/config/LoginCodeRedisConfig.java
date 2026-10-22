package kr.co.cking.auth.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Login Code를 원자적으로 소비하는 Redis Lua 스크립트를 등록한다. */
@Configuration
public class LoginCodeRedisConfig {

    /** Code 값을 읽고 즉시 삭제하는 단일 Redis 명령 스크립트를 생성한다. */
    @Bean
    public DefaultRedisScript<String> loginCodeConsumeScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/login-code-consume.lua"));
        script.setResultType(String.class);
        return script;
    }
}
