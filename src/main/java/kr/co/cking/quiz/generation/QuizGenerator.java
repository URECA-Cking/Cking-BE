package kr.co.cking.quiz.generation;

/** 퀴즈 생성 입력을 받아 생성 결과를 반환하는 Provider의 공통 계약이다. */
public interface QuizGenerator {

    GeneratedQuiz generate(QuizGenerationInput input);
}
