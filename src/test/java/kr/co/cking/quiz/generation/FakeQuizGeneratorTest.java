package kr.co.cking.quiz.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FakeQuizGeneratorTest {

    private final FakeQuizGenerator generator = new FakeQuizGenerator();

    @Test
    void 외부_AI없이_요청한_수와_선택지_수에_맞는_결정적_결과를_생성한다() {
        QuizGenerationInput input = new QuizGenerationInput("고양이는 포유류다.", 2, 3);

        GeneratedQuiz first = generator.generate(input);
        GeneratedQuiz second = generator.generate(input);

        assertThat(first).isEqualTo(second);
        assertThat(first.questions()).hasSize(2);
        assertThat(first.questions()).allSatisfy(question -> {
            assertThat(question.options()).hasSize(3);
            assertThat(question.correctOptionIndex()).isZero();
            assertThat(input.contentText()).contains(question.sourceEvidence());
        });
    }
}
