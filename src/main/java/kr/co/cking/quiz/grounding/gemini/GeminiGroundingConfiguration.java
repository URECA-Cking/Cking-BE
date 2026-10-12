package kr.co.cking.quiz.grounding.gemini;

import kr.co.cking.quiz.grounding.VideoGrounder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/** API key가 없어도 애플리케이션을 기동할 수 있도록 호출 시점에만 key를 검증한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiGroundingProperties.class)
public class GeminiGroundingConfiguration {

    @Bean
    VideoGrounder geminiVideoGrounder(GeminiGroundingProperties properties, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(safeDuration(properties.getConnectTimeout(), Duration.ofSeconds(2)));
        requestFactory.setReadTimeout(safeDuration(properties.getReadTimeout(), Duration.ofSeconds(30)));
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return new GeminiVideoGrounder(restClient, properties, objectMapper);
    }

    private Duration safeDuration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
