package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedrawRequestFailurePersistenceServiceTest {

    @Test
    void 명명된_예외의_클래스명을_실패_코드로_사용한다() {
        String failureCode = RedrawRequestFailurePersistenceService.failureCodeOf(new IllegalStateException());

        assertThat(failureCode).isEqualTo("IllegalStateException");
    }

    @Test
    void 긴_예외_클래스명은_실패_코드_컬럼_길이로_제한한다() {
        String failureCode = RedrawRequestFailurePersistenceService
                .failureCodeOf(new RedrawExecutionInfrastructureInitializationFailureException());

        assertThat(failureCode).hasSize(50)
                .isEqualTo(RedrawExecutionInfrastructureInitializationFailureException.class.getSimpleName()
                        .substring(0, 50));
    }

    @Test
    void 익명_예외는_명명된_상위_예외의_클래스명을_실패_코드로_사용한다() {
        RuntimeException exception = new NamedRuntimeException() {
        };

        String failureCode = RedrawRequestFailurePersistenceService.failureCodeOf(exception);

        assertThat(failureCode).isEqualTo(NamedRuntimeException.class.getSimpleName());
    }

    static class RedrawExecutionInfrastructureInitializationFailureException extends RuntimeException {
    }

    static class NamedRuntimeException extends RuntimeException {
    }
}
