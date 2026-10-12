package kr.co.cking.quiz.application.dto;

/** 응시 결과 응답의 Ticket 지급 상태(이슈 #543). PAID만 실제 지급 완료다. */
public enum QuizRewardPaymentStatus {
    /** 정답이 0개라 지급 대상이 아니다. */
    NOT_ELIGIBLE,
    /** 아직 접수되지 않았거나 지급을 처리하는 중이다(RECEIVED·EXECUTING·ACCEPTED·RETRYABLE_FAILURE). */
    PENDING,
    /** Ticket 원장·잔액에 반영됐다(APPLIED). */
    PAID,
    /** 지급 여부를 확인할 수 없어 확인이 필요하다(UNKNOWN). 자동으로 다시 실행하지 않는다. */
    DELAYED,
    /** 지급 상태를 조회하지 못했다. 미지급을 뜻하지 않는다. */
    UNAVAILABLE
}
