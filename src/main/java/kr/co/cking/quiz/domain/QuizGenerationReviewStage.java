package kr.co.cking.quiz.domain;

/**
 * Quiz 생성 전 Grounding 원문 검토 흐름의 세부 단계(이슈 #602). 작업 상태는 {@link QuizGenerationStatus}를 그대로 쓰고,
 * 단계가 없으면(null) 검토 없이 Grounding과 생성을 이어서 하는 기존 작업이다.
 */
public enum QuizGenerationReviewStage {
    /** 영상 Grounding 대기·진행 중이거나 그 단계에서 끝났다. AI 원문이 아직 없다. */
    GROUNDING,
    /** AI 원문과 facts를 저장했고 Creator 확정을 기다린다. 작업 상태는 RUNNING이다. */
    AWAITING_REVIEW,
    /** Creator가 확정한 원문으로 Quiz를 생성한다. */
    GENERATING
}
