package kr.co.cking.redraw.application;

import kr.co.cking.common.exception.BusinessException;

/** 시스템3 실행 중 발생한 업무 예외를 실행 전 요청 검증 예외와 구분한다. */
class RedrawDrawingExecutionBusinessFailureException extends RuntimeException {

    private final BusinessException businessException;

    RedrawDrawingExecutionBusinessFailureException(BusinessException businessException) {
        super(businessException);
        this.businessException = businessException;
    }

    BusinessException businessException() {
        return businessException;
    }
}
