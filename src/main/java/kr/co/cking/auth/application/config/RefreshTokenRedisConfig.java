package kr.co.cking.auth.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Refresh Token의 원자적 rotation Lua 스크립트를 등록한다. */
@Configuration
public class RefreshTokenRedisConfig {

    /** 이전 key를 삭제하고 다음 hash key를 생성하는 Lua 스크립트를 제공한다. */
    @Bean
    public DefaultRedisScript<String> refreshTokenRotateScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/refresh-token-rotate.lua"));
        script.setResultType(String.class);
        return script;
    }
}
