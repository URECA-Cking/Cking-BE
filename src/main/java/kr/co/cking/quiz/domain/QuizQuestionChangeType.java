package kr.co.cking.quiz.domain;

/**
 * Creator가 구성한 revision의 문항이 원본 AI 문항과 어떤 관계인지. AI 생성 revision의 원본 문항은 값이 없다(null).
 *
 * <p>AI 문항의 변경 여부는 Creator의 주장이 아니라 원본 문항과 내용(질문·보기·정답·해설·근거)을 비교해 정한다.
 * 수정 문항은 Judge 재평가 전(NOT_REVALIDATED), 추가 문항은 평가 대상이 아니었던(NOT_EVALUATED) 문항이다.
 */
public enum QuizQuestionChangeType {
    AI_UNCHANGED,
    CREATOR_EDITED,
    CREATOR_ADDED;

    /** 원본 AI 문항과 내용이 같으면 {@link #AI_UNCHANGED}, 하나라도 다르면 {@link #CREATOR_EDITED}. */
    public static QuizQuestionChangeType ofAiQuestion(QuizRevisionFingerprint.Question original,
                                                      QuizRevisionFingerprint.Question current) {
        return original.equals(current) ? AI_UNCHANGED : CREATOR_EDITED;
    }
}
