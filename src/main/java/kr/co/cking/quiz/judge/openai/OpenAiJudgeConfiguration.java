package kr.co.cking.quiz.judge.openai;

import kr.co.cking.quiz.judge.QuizJudge;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** API key가 없어도 애플리케이션을 기동할 수 있도록 호출 시점에만 key를 검증한다. 그 밖의 설정은 기동 시 검증한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiJudgeProperties.class)
public class OpenAiJudgeConfiguration {

    @Bean
    QuizJudge openAiQuizJudge(OpenAiJudgeProperties properties, ObjectMapper objectMapper) {
        return new OpenAiQuizJudge(properties, objectMapper,
                (connectTimeout, readTimeout) -> {
                    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
                    requestFactory.setConnectTimeout(connectTimeout);
                    requestFactory.setReadTimeout(readTimeout);
                    return RestClient.builder()
                            .baseUrl(properties.getBaseUrl())
                            .requestFactory(requestFactory)
                            .build();
                },
                System::nanoTime,
                duration -> Thread.sleep(duration.toMillis()),
                Clock.systemUTC());
    }
}
