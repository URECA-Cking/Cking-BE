package kr.co.cking.quiz.generation;

/** 프롬프트와 출력 계약을 함께 버전 관리하기 위한 식별자다. */
public enum QuizPromptVersion {
    V1("quiz-mcq-v1");

    private final String value;

    QuizPromptVersion(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
