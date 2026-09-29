package kr.co.cking.quiz.generation.gemini;

import java.time.Duration;

import kr.co.cking.quiz.generation.QuizGenerationValidator;
import kr.co.cking.quiz.generation.QuizGenerator;
import kr.co.cking.quiz.generation.QuizPromptTemplate;
import kr.co.cking.quiz.generation.QuizPromptVersion;
import kr.co.cking.quiz.generation.QuizResponseParser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** API key가 없어도 애플리케이션을 기동할 수 있도록 호출 시점에만 key를 검증한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiQuizProperties.class)
public class GeminiConfiguration {

    @Bean
    GeminiApiClient geminiApiClient(GeminiQuizProperties properties, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(safeDuration(properties.getConnectTimeout(), Duration.ofSeconds(2)));
        requestFactory.setReadTimeout(safeDuration(properties.getReadTimeout(), Duration.ofSeconds(30)));
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return new GeminiRestClient(restClient, properties, objectMapper);
    }

    @Bean
    QuizResponseParser quizResponseParser(ObjectMapper objectMapper) {
        return new QuizResponseParser(objectMapper);
    }

    @Bean
    QuizGenerationValidator quizGenerationValidator() {
        return new QuizGenerationValidator();
    }

    @Bean
    QuizGenerator geminiQuizGenerator(GeminiApiClient geminiApiClient,
                                      QuizResponseParser responseParser,
                                      QuizGenerationValidator validator) {
        return new GeminiQuizGenerator(
                geminiApiClient,
                QuizPromptTemplate.forVersion(QuizPromptVersion.V1),
                responseParser,
                validator);
    }

    private Duration safeDuration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
