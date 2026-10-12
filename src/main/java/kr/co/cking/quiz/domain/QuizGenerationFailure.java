package kr.co.cking.quiz.domain;

/** Quiz 생성 작업 실패 사유. Creator에게 그대로 보여 주는 안전한 코드이며 Provider 오류 원문은 담지 않는다. */
public enum QuizGenerationFailure {
    /** 영상 Grounding을 Provider가 거부했다(비공개 영상 등). */
    GROUNDING_REJECTED,
    /** 영상 Grounding 응답이 계약을 지키지 않았다. */
    GROUNDING_INVALID,
    /** Quiz 생성 결과를 해석하거나 검증하지 못했다. */
    GENERATION_INVALID,
    /** 생성 결과가 Quiz 저장 조건(길이 상한 등)을 지키지 않았다. */
    DRAFT_INVALID,
    /** Provider 오류, 재시도 소진, 설정 누락. */
    PROVIDER_ERROR,
    /** Provider 응답 시간이 초과돼 결과를 알 수 없다. UNKNOWN 상태와 함께 쓴다. */
    PROVIDER_TIMEOUT,
    /** 생성 Executor가 가득 차 작업을 받지 못했다. */
    DISPATCH_REJECTED,
    /** 기준 시간 안에 Worker가 시작하지 않았다. */
    STALE_PENDING,
    /** 기준 시간 안에 끝나지 않아 결과를 알 수 없다. UNKNOWN 상태와 함께 쓴다. */
    STALE_RUNNING,
    /** 예상하지 못한 서버 오류. */
    INTERNAL_ERROR
}
