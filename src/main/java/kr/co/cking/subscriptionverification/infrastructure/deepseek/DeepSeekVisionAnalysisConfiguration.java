package kr.co.cking.subscriptionverification.infrastructure.deepseek;

import java.time.Duration;

import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** DeepSeek HTTP Client와 Provider 독립 VisionAnalysisPort 구현을 조립한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeepSeekVisionAnalysisProperties.class)
public class DeepSeekVisionAnalysisConfiguration {

    /** connect·read timeout을 분리한 DeepSeek 전용 HTTP Client를 만든다. */
    @Bean
    RestClient deepSeekRestClient(DeepSeekVisionAnalysisProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(safeDuration(properties.getConnectTimeout(), Duration.ofSeconds(2)));
        requestFactory.setReadTimeout(safeDuration(properties.getReadTimeout(), Duration.ofSeconds(30)));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /** Provider JSON을 엄격한 Vision 결과로 변환하는 Parser를 만든다. */
    @Bean
    DeepSeekVisionAnalysisResponseParser deepSeekVisionAnalysisResponseParser(ObjectMapper objectMapper) {
        return new DeepSeekVisionAnalysisResponseParser(objectMapper);
    }

    /** Application이 사용할 Provider 독립 VisionAnalysisPort 구현을 만든다. */
    @Bean
    VisionAnalysisPort deepSeekVisionAnalysisPort(
            RestClient deepSeekRestClient,
            DeepSeekVisionAnalysisProperties properties,
            DeepSeekVisionAnalysisResponseParser responseParser,
            ObjectMapper objectMapper) {
        return new DeepSeekVisionAnalysisAdapter(deepSeekRestClient, properties, responseParser, objectMapper);
    }

    /** 누락·0·음수 timeout 설정을 안전한 기본값으로 대체한다. */
    private Duration safeDuration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
