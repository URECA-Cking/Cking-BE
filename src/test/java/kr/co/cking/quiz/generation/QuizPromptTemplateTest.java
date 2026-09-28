package kr.co.cking.quiz.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuizPromptTemplateTest {

    @Test
    void 프롬프트에_버전과_요청_조건과_콘텐츠가_포함된다() {
        QuizGenerationInput input = new QuizGenerationInput("고양이는 포유류다.", 2, 4);

        String prompt = QuizPromptTemplate.forVersion(QuizPromptVersion.V1).render(input);

        assertThat(prompt).contains(QuizPromptVersion.V1.value());
        assertThat(prompt).contains("2").contains("4").contains(input.contentText());
        assertThat(prompt).contains("correctOptionIndex").contains("sourceEvidence");
    }
}
