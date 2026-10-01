package kr.co.cking.subscriptionverification.infrastructure.gemini;

import java.time.Duration;

import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisPort;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Gemini HTTP Client와 Provider 독립 VisionAnalysisPort 구현을 조립한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiVisionAnalysisProperties.class)
public class GeminiVisionAnalysisConfiguration {

    @Bean
    InitializingBean geminiSubscriptionConfigurationValidator(
            GeminiVisionAnalysisProperties properties,
            @Value("${cking.verification.youtube-subscription.submission-enabled:false}")
                    boolean submissionEnabled) {
        return new GeminiVisionAnalysisConfigurationValidator(properties, submissionEnabled);
    }

    @Bean
    RestClient geminiVisionRestClient(GeminiVisionAnalysisProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(safeDuration(properties.getConnectTimeout(), Duration.ofSeconds(2)));
        requestFactory.setReadTimeout(safeDuration(properties.getReadTimeout(), Duration.ofSeconds(30)));
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    GeminiVisionAnalysisResponseParser geminiVisionAnalysisResponseParser(ObjectMapper objectMapper) {
        return new GeminiVisionAnalysisResponseParser(objectMapper);
    }

    @Bean
    VisionAnalysisPort geminiVisionAnalysisPort(
            RestClient geminiVisionRestClient,
            GeminiVisionAnalysisProperties properties,
            GeminiVisionAnalysisResponseParser responseParser,
            ObjectMapper objectMapper) {
        return new GeminiVisionAnalysisAdapter(geminiVisionRestClient, properties, responseParser, objectMapper);
    }

    private Duration safeDuration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
