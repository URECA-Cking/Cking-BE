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

/** 실제 DB에서 RedrawRequest 생성의 동시성·결원 중복 점유 방어를 검증한다. */
@SpringBootTest
class RedrawRequestCreateConcurrencyIntegrationTest extends RedrawRequestCreateIntegrationFixture {

    /** 같은 멱등 키 요청을 동시에 보내도 잠금 후 기존 요청을 재사용해 하나만 생성한다. */
    @Test
    void 같은_멱등_키의_동시_재시도는_기존_요청을_재사용한다() throws Exception {
        String idempotencyKey = newIdempotencyKey();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RedrawRequestCreateResult> first = executor.submit(
                    () -> createAfterSignal(ready, start, idempotencyKey)
            );
            Future<RedrawRequestCreateResult> second = executor.submit(
                    () -> createAfterSignal(ready, start, idempotencyKey)
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<RedrawRequestCreateResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertThat(results).extracting(RedrawRequestCreateResult::redrawRequestId)
                    .containsOnly(results.getFirst().redrawRequestId());
            assertThat(results).extracting(RedrawRequestCreateResult::created).containsExactlyInAnyOrder(true, false);
            assertThat(redrawRequestCount()).isEqualTo(1);
        }
    }

    /** 서로 다른 멱등 키의 동시 요청도 같은 Winner 결원을 중복 점유하지 못하게 한다. */
    @Test
    void 다른_멱등_키의_동시_요청은_결원을_한번만_점유한다() throws Exception {
        String firstIdempotencyKey = newIdempotencyKey();
        String secondIdempotencyKey = newIdempotencyKey();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> createOrReturnError(ready, start, firstIdempotencyKey));
            Future<String> second = executor.submit(() -> createOrReturnError(ready, start, secondIdempotencyKey));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "NO_REDRAW_VACANCY");
            assertThat(redrawRequestCount()).isEqualTo(1);
            assertThat(vacancyCount()).isEqualTo(1);
        }
    }

    /** 시작 신호 뒤 지정한 멱등 키로 생성 명령을 실행한다. */
    private RedrawRequestCreateResult createAfterSignal(
            CountDownLatch ready,
            CountDownLatch start,
            String idempotencyKey
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return redrawRequestCreateService.create(command(idempotencyKey));
    }

    /** 병렬 생성 결과를 성공 또는 도메인 오류 코드 문자열로 변환한다. */
    private String createOrReturnError(CountDownLatch ready, CountDownLatch start, String idempotencyKey)
            throws InterruptedException {
        try {
            createAfterSignal(ready, start, idempotencyKey);
            return "SUCCESS";
        } catch (BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }
}
