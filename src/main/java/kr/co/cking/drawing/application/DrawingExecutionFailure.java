package kr.co.cking.drawing.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.DrawingFailureStage;

/** 결과 트랜잭션 밖에서 실패 단계와 안정적인 실패 코드를 보존하기 위한 내부 예외다. */
final class DrawingExecutionFailure extends RuntimeException {

    private final DrawingFailureStage stage;
    private final String failureCode;

    private DrawingExecutionFailure(DrawingFailureStage stage, String failureCode, RuntimeException cause) {
        super(cause.getMessage(), cause);
        this.stage = stage;
        this.failureCode = failureCode;
    }

    static DrawingExecutionFailure at(DrawingFailureStage stage, RuntimeException cause) {
        String code = cause instanceof BusinessException businessException
                ? businessException.getErrorCode().code()
                : "SYSTEM_ERROR";
        return new DrawingExecutionFailure(stage, code, cause);
    }

    DrawingFailureStage stage() {
        return stage;
    }

    String failureCode() {
        return failureCode;
    }

    RuntimeException original() {
        return (RuntimeException) getCause();
    }
}
