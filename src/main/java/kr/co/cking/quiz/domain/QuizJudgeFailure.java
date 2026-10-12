package kr.co.cking.quiz.domain;

/**
 * 평가 Job이 FAILED·UNKNOWN으로 끝난 사유(이슈 #529). Creator에게 보여 주는 안전한 코드이며 Provider 원문은 담지 않는다.
 * 어느 사유든 한도 안에서 새 requestId로 명시적으로 재평가할 수 있고, 자동으로 다시 실행하지 않는다.
 */
public enum QuizJudgeFailure {

    /** UNKNOWN. HTTP 응답 대기 시간이 초과됐다. */
    PROVIDER_TIMEOUT,
    /** UNKNOWN. 평가 한 번의 전체 실행 시간이 초과됐다. */
    EXECUTION_TIMEOUT,
    /** UNKNOWN. 실행 중 thread가 중단됐다. */
    EXECUTION_INTERRUPTED,
    /** FAILED. 429·5xx 재시도 소진, 네트워크 오류, Provider 실패 상태. */
    PROVIDER_ERROR,
    /** FAILED. Provider가 요청을 거부했다(429를 제외한 4xx). */
    PROVIDER_REJECTED,
    /** FAILED. 모델이 평가를 거부했다. */
    PROVIDER_REFUSAL,
    /** FAILED. 출력 token 한도 등으로 응답이 끝나지 않았다. */
    INCOMPLETE_RESPONSE,
    /** FAILED. 응답이 schema·문항 번호·reason 규칙을 지키지 않았다. */
    INVALID_RESPONSE,
    /** FAILED. 실행 시점에 Provider 설정(API key 등)이 없었다. */
    NOT_CONFIGURED,
    /** FAILED. 실행 시점의 설정 fingerprint가 series에 고정한 값과 달라 Provider를 호출하지 않았다. */
    SETTINGS_MISMATCH,
    /** FAILED. revision·입력·결과가 series에 고정한 값이나 저장 내용과 맞지 않는다. */
    INTEGRITY_FAILED,
    /** FAILED. Executor가 작업을 받지 못했다. */
    DISPATCH_REJECTED,
    /** FAILED. 기준 시간 안에 Worker가 시작하지 않았다. */
    STALE_PENDING,
    /** UNKNOWN. 기준 시간 안에 끝나지 않아 결과를 알 수 없다. */
    STALE_RUNNING,
    /** FAILED. 예상하지 못한 서버 오류. */
    INTERNAL_ERROR
}
