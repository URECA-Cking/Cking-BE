package kr.co.cking.common.image;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageProcessingLimiterTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        executor.shutdownNow();
    }

    @Test
    void 한도만큼은_동시에_실행된다() throws Exception {
        ImageProcessingLimiter limiter = new ImageProcessingLimiter(2, Duration.ofSeconds(5));
        CountDownLatch started = new CountDownLatch(2);

        occupy(limiter, started);
        occupy(limiter, started);

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void 한도를_넘으면_대기_시간_뒤_IMAGE_PROCESSING_BUSY를_던진다() throws Exception {
        ImageProcessingLimiter limiter = new ImageProcessingLimiter(1, Duration.ofMillis(200));
        CountDownLatch started = new CountDownLatch(1);
        occupy(limiter, started);
        started.await(5, TimeUnit.SECONDS);

        long begin = System.nanoTime();
        assertThatThrownBy(() -> limiter.run(() -> "late"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.IMAGE_PROCESSING_BUSY));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isGreaterThanOrEqualTo(150);
    }

    @Test
    void 앞_작업이_끝나면_기다리던_작업이_실행된다() throws Exception {
        ImageProcessingLimiter limiter = new ImageProcessingLimiter(1, Duration.ofSeconds(5));
        CountDownLatch started = new CountDownLatch(1);
        occupy(limiter, started);
        started.await(5, TimeUnit.SECONDS);

        CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> limiter.run(() -> "done"), executor);
        release.countDown();

        assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("done");
    }

    @Test
    void 작업이_예외를_던져도_자리를_돌려준다() {
        ImageProcessingLimiter limiter = new ImageProcessingLimiter(1, Duration.ofMillis(200));

        assertThatThrownBy(() -> limiter.run(() -> {
            throw new IllegalStateException("decode failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(limiter.run(() -> "next")).isEqualTo("next");
    }

    private void occupy(ImageProcessingLimiter limiter, CountDownLatch started) {
        executor.submit(() -> limiter.run(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
    }
}
