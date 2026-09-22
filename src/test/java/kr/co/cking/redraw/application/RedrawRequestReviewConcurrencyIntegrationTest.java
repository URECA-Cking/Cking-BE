package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 실제 DB에서 같은 RedrawRequest의 승인·거절 동시 충돌 방어를 검증한다. */
@SpringBootTest
class RedrawRequestReviewConcurrencyIntegrationTest extends RedrawRequestCreateIntegrationFixture {

    /** 동시에 승인·거절해도 행 잠금으로 하나만 심사되고 다른 하나는 상태 오류가 된다. */
    @Test
    void 동시_승인_거절은_하나만_상태_전이한다() throws Exception {
        Long redrawRequestId = redrawRequestCreateService.create(command(newIdempotencyKey())).redrawRequestId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> approve = executor.submit(() -> approveAfterSignal(ready, start, redrawRequestId));
            Future<String> reject = executor.submit(() -> rejectAfterSignal(ready, start, redrawRequestId));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(approve.get(10, TimeUnit.SECONDS), reject.get(10, TimeUnit.SECONDS)))
                    .contains("INVALID_STATE")
                    .containsAnyOf("APPROVED", "REJECTED");
        }
    }

    /** 시작 신호 뒤 승인 결과 또는 업무 오류 코드를 반환한다. */
    private String approveAfterSignal(CountDownLatch ready, CountDownLatch start, Long redrawRequestId)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return redrawRequestReviewService.approve(adminId(), redrawRequestId).status().name();
        } catch (BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }

    /** 시작 신호 뒤 거절 결과 또는 업무 오류 코드를 반환한다. */
    private String rejectAfterSignal(CountDownLatch ready, CountDownLatch start, Long redrawRequestId)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return redrawRequestReviewService.reject(adminId(), redrawRequestId, "결원 확인이 필요합니다.").status().name();
        } catch (BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }
}
