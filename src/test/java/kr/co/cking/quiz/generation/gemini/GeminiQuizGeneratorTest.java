package kr.co.cking.quiz.generation.gemini;

import kr.co.cking.quiz.generation.GeneratedQuestion;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.generation.QuizGenerationValidationException;
import kr.co.cking.quiz.generation.QuizGenerationValidator;
import kr.co.cking.quiz.generation.QuizPromptTemplate;
import kr.co.cking.quiz.generation.QuizPromptVersion;
import kr.co.cking.quiz.generation.QuizResponseParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeminiQuizGeneratorTest {

    private static final QuizGenerationInput INPUT =
            new QuizGenerationInput("서울은 대한민국의 수도다.", 1, 4);

    @Mock
    private GeminiApiClient apiClient;

    @Mock
    private QuizResponseParser parser;

    @Mock
    private QuizGenerationValidator validator;

    @Test
    void input부터_parser와_validator를_거쳐_퀴즈를_반환한다() {
        GeneratedQuiz expected = validQuiz();
        when(apiClient.generateContent(anyString(), eq(1), eq(4))).thenReturn("provider-json");
        when(parser.parse("provider-json")).thenReturn(expected);

        GeminiQuizGenerator generator = generator();

        assertThat(generator.generate(INPUT)).isSameAs(expected);

        InOrder order = inOrder(validator, apiClient, parser);
        order.verify(validator).validateInput(INPUT);
        order.verify(apiClient).generateContent(anyString(), eq(1), eq(4));
        order.verify(parser).parse("provider-json");
        order.verify(validator).validate(INPUT, expected);
    }

    @Test
    void 입력이_유효하지_않으면_Gemini를_호출하지_않는다() {
        QuizGenerationInput invalid = new QuizGenerationInput(" ", 1, 4);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("invalid"))
                .when(validator).validateInput(invalid);

        assertThatThrownBy(() -> generator().generate(invalid))
                .isInstanceOf(IllegalArgumentException.class);

        verify(apiClient, never()).generateContent(anyString(), eq(1), eq(4));
    }

    @Test
    void Parser_실패는_Provider_재호출없이_그대로_전달된다() {
        when(apiClient.generateContent(anyString(), eq(1), eq(4))).thenReturn("bad-json");
        when(parser.parse("bad-json")).thenThrow(new RuntimeException("parse"));

        assertThatThrownBy(() -> generator().generate(INPUT))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("parse");

        verify(apiClient).generateContent(anyString(), eq(1), eq(4));
    }

    @Test
    void Validator_실패는_Provider_재호출없이_전달된다() {
        GeneratedQuiz invalid = new GeneratedQuiz(List.of(new GeneratedQuestion(
                "질문", List.of("하나", "둘", "셋", "넷"), 0, "설명", "근거")),
                QuizPromptVersion.V1.value());
        when(apiClient.generateContent(anyString(), eq(1), eq(4))).thenReturn("provider-json");
        when(parser.parse("provider-json")).thenReturn(invalid);
        org.mockito.Mockito.doThrow(new QuizGenerationValidationException("invalid result"))
                .when(validator).validate(INPUT, invalid);

        assertThatThrownBy(() -> generator().generate(INPUT))
                .isInstanceOf(QuizGenerationValidationException.class);

        verify(apiClient).generateContent(anyString(), eq(1), eq(4));
    }

    @Test
    void 모델이_다른_promptVersion을_반환하면_결과를_거부한다() {
        GeneratedQuiz wrongVersion = new GeneratedQuiz(validQuiz().questions(), "quiz-mcq-v2");
        when(apiClient.generateContent(anyString(), eq(1), eq(4))).thenReturn("provider-json");
        when(parser.parse("provider-json")).thenReturn(wrongVersion);

        assertThatThrownBy(() -> generator().generate(INPUT))
                .isInstanceOf(QuizGenerationValidationException.class);

        verify(apiClient).generateContent(anyString(), eq(1), eq(4));
    }

    private GeminiQuizGenerator generator() {
        return new GeminiQuizGenerator(
                apiClient,
                QuizPromptTemplate.forVersion(QuizPromptVersion.V1),
                parser,
                validator
        );
    }

    private GeneratedQuiz validQuiz() {
        return new GeneratedQuiz(List.of(new GeneratedQuestion(
                "대한민국의 수도는?",
                List.of("서울", "부산", "대전", "광주"),
                0,
                "서울은 대한민국의 수도입니다.",
                INPUT.contentText()
        )), QuizPromptVersion.V1.value());
    }
}
