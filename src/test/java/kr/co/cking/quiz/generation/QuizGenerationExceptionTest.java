package kr.co.cking.quiz.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuizGenerationExceptionTest {

    @Test
    void Provider_타임아웃과_일반오류는_구분된_내부예외다() {
        QuizProviderTimeoutException timeout = new QuizProviderTimeoutException("timeout", null);
        QuizProviderException provider = new QuizProviderException("provider");

        assertThat(timeout).isInstanceOf(QuizProviderException.class)
                .isInstanceOf(QuizGenerationException.class);
        assertThat(provider).isInstanceOf(QuizGenerationException.class)
                .isNotInstanceOf(QuizProviderTimeoutException.class);
    }
}
