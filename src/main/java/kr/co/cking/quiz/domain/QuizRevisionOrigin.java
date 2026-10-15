package kr.co.cking.quiz.domain;

/** revision을 만든 주체. AI 생성은 quiz-revision-v1, Creator 구성은 quiz-revision-v2 해시를 쓴다. */
public enum QuizRevisionOrigin {
    AI_GENERATED,
    CREATOR_COMPOSED
}
