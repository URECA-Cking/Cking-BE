package kr.co.cking.quiz.generation;

import java.util.List;

/** 외부 Provider 응답을 검증한 객관식 퀴즈 결과다. */
public record GeneratedQuiz(
        List<GeneratedQuestion> questions,
        String promptVersion
) {
}
