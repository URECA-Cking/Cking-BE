package kr.co.cking.event.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import kr.co.cking.event.application.dto.CachedEvent;

@Configuration
public class EventCacheConfig {

    @Bean
    public RedisTemplate<String, CachedEvent> eventRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, CachedEvent> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }
}
