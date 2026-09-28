package kr.co.cking.quiz.generation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizResponseParserTest {

    private final QuizResponseParser parser = new QuizResponseParser(new ObjectMapper());

    @Test
    void JSON_응답을_생성_퀴즈로_파싱한다() {
        String json = """
                {
                  "questions": [
                    {
                      "question": "대한민국의 수도는?",
                      "options": ["서울", "부산", "대전", "광주"],
                      "correctOptionIndex": 0,
                      "explanation": "서울은 대한민국의 수도입니다.",
                      "sourceEvidence": "대한민국의 수도는 서울이다."
                    }
                  ],
                  "promptVersion": "quiz-mcq-v1"
                }
                """;

        GeneratedQuiz result = parser.parse(json);

        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().getFirst().correctOptionIndex()).isZero();
        assertThat(result.promptVersion()).isEqualTo("quiz-mcq-v1");
    }

    @Test
    void 잘못된_JSON은_파싱오류다() {
        assertThatThrownBy(() -> parser.parse("{\"questions\":["))
                .isInstanceOf(QuizResponseParseException.class);
    }

    @Test
    void 필드_타입이_잘못된_JSON은_파싱오류다() {
        String json = """
                {"questions":[{"question":"질문","options":["A","B"],
                "correctOptionIndex":"0","explanation":"설명","sourceEvidence":"원문"}]}
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(QuizResponseParseException.class)
                .hasMessageContaining("correctOptionIndex");
    }
}
