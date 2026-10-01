package kr.co.cking.subscriptionverification.infrastructure.gemini;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GeminiVisionAnalysisConfigurationValidatorTest {

    @Test
    void 제출_기능이_비활성화되면_API_Key가_없어도_기동할_수_있다() {
        GeminiVisionAnalysisProperties properties = new GeminiVisionAnalysisProperties();

        GeminiVisionAnalysisConfigurationValidator validator =
                new GeminiVisionAnalysisConfigurationValidator(properties, false);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void 제출_기능이_활성화되면_API_Key가_필수다() {
        GeminiVisionAnalysisProperties properties = new GeminiVisionAnalysisProperties();

        GeminiVisionAnalysisConfigurationValidator validator =
                new GeminiVisionAnalysisConfigurationValidator(properties, true);

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUBSCRIPTION_GEMINI_API_KEY");
    }

    @Test
    void 제출_기능과_API_Key가_모두_설정되면_기동할_수_있다() {
        GeminiVisionAnalysisProperties properties = new GeminiVisionAnalysisProperties();
        properties.setApiKey("test-gemini-key");

        GeminiVisionAnalysisConfigurationValidator validator =
                new GeminiVisionAnalysisConfigurationValidator(properties, true);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }
}
