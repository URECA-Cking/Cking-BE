package kr.co.cking.quiz.domain;

/**
 * Quiz 생성 작업 상태. 허용 전이는 PENDING → RUNNING·FAILED, RUNNING → SUCCEEDED·FAILED·UNKNOWN뿐이며
 * SUCCEEDED·FAILED·UNKNOWN은 끝 상태다.
 */
public enum QuizGenerationStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** 시간 초과 등으로 Provider 결과를 확인할 수 없다. 이후 결과를 저장하지 않는다. */
    UNKNOWN
}
