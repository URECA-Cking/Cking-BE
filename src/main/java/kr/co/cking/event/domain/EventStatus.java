package kr.co.cking.event.domain;

public enum EventStatus {
    DRAFT,
    PENDING_APPROVAL,
    REJECTED,
    SCHEDULED,
    OPEN,
    CLOSING,
    CLOSED,
    DRAW_COMPLETED,
    PUBLISHED;

    /**
     * 승인 전/거절 상태는 조회 API(§4.1 displayStatus 매핑 대상 6종)에 노출하지 않는다.
     * 이 판정을 벗어난 이벤트가 DisplayStatus.of()로 들어가면 정의되지 않은 상태로 예외가 난다.
     */
    public boolean isPubliclyVisible() {
        return this != DRAFT && this != PENDING_APPROVAL && this != REJECTED;
    }
}
