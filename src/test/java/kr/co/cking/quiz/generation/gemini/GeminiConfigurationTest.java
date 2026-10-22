package kr.co.cking.quiz.generation.gemini;

import kr.co.cking.quiz.generation.QuizGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiConfigurationTest {

    @Test
    void 다른_RestClient_빈이_있어도_Gemini_설정은_독립적으로_생성된다() {
        new ApplicationContextRunner()
                .withUserConfiguration(GeminiConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(RestClient.class, () -> RestClient.builder().baseUrl("http://localhost").build())
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(QuizGenerator.class);
                    assertThat(context.getBeansOfType(RestClient.class)).hasSize(1);
                });
    }
}
