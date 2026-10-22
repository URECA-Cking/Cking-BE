package kr.co.cking.drawing.domain;

/** 실패 이력에서 어느 실행 단계가 중단됐는지 식별하는 안정적인 코드다. */
public enum DrawingFailureStage {
    INPUT_VERIFICATION,
    DRAWING_ENGINE,
    PRIZE_ALLOCATION,
    RESULT_PERSISTENCE,
    EVENT_TRANSITION,
    SERVER_INTERRUPTED
}
