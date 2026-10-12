package kr.co.cking.quiz.grounding.gemini;

import kr.co.cking.quiz.generation.QuizGenerator;
import kr.co.cking.quiz.generation.gemini.GeminiConfiguration;
import kr.co.cking.quiz.generation.gemini.GeminiQuizProperties;
import kr.co.cking.quiz.grounding.VideoGrounder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiGroundingConfigurationTest {

    @Test
    void Grounding_설정은_Quiz_생성_설정과_분리되고_API_key만_같은_값을_쓴다() {
        new ApplicationContextRunner()
                .withUserConfiguration(GeminiConfiguration.class, GeminiGroundingConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "cking.quiz.gemini.api-key=shared-key",
                        "cking.quiz.gemini.read-timeout=PT30S",
                        "cking.quiz.grounding.api-key=shared-key",
                        "cking.quiz.grounding.model=grounding-model",
                        "cking.quiz.grounding.read-timeout=PT90S",
                        "cking.quiz.grounding.max-output-tokens=")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(VideoGrounder.class)
                            .hasSingleBean(QuizGenerator.class);
                    GeminiGroundingProperties grounding = context.getBean(GeminiGroundingProperties.class);
                    GeminiQuizProperties quiz = context.getBean(GeminiQuizProperties.class);
                    assertThat(grounding.getApiKey()).isEqualTo(quiz.getApiKey());
                    assertThat(grounding.getModel()).isEqualTo("grounding-model");
                    assertThat(grounding.getReadTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(quiz.getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(grounding.getMaxAttempts()).isEqualTo(3);
                    assertThat(grounding.getMaxOutputTokens()).isNull();
                });
    }
}
