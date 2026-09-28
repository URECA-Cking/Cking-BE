package kr.co.cking.quiz.generation;

/**
 * 퀴즈 생성 Provider의 애플리케이션 경계다.
 *
 * <p>실제 외부 Provider는 모델과 인증 정책이 확정된 별도 작업에서 이 계약을
 * 구현한다. 이번 PR의 Fake 구현은 이 인터페이스를 테스트에서만 직접 사용한다.</p>
 */
public interface QuizGenerator {

    GeneratedQuiz generate(QuizGenerationInput input);
}
