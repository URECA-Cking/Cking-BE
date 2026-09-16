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

    public boolean isPubliclyVisible() {
        return this != DRAFT && this != PENDING_APPROVAL && this != REJECTED;
    }
}
