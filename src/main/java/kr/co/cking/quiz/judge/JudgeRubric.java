package kr.co.cking.quiz.judge;

/**
 * 문항마다 평가하는 7개 rubric 항목. 선언 순서가 prompt·schema·hash의 순서다. 항목이나 순서를 바꾸면
 * {@link QuizJudgePrompt#RUBRIC_VERSION}과 {@link QuizJudgePrompt#SCHEMA_VERSION}을 올린다.
 */
public enum JudgeRubric {

    TEXT_ANSWER_CORRECT("textAnswerCorrect"),
    UNIQUE_ANSWER("uniqueAnswer"),
    EVIDENCE_SUPPORTS_ANSWER("evidenceSupportsAnswer"),
    QUESTION_CLARITY("questionClarity"),
    KOREAN_QUALITY("koreanQuality"),
    DISTRACTOR_QUALITY("distractorQuality"),
    TEXT_FAITHFULNESS("textFaithfulness");

    private final String field;

    JudgeRubric(String field) {
        this.field = field;
    }

    /** Provider 응답 schema의 필드 이름. */
    public String field() {
        return field;
    }
}
