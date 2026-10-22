package kr.co.cking.common.image;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 업로드 컨트롤러 테스트용 이미지 처리 한도. 입장 자리는 1개다. */
public final class ImageProcessingTestSupport {

    private ImageProcessingTestSupport() {
    }

    /** 다른 스레드가 입장 자리를 차지한 상태를 만든다. 닫으면 자리를 돌려준다. */
    public static AutoCloseable occupyAdmission(ImageProcessingLimiter limiter) throws InterruptedException {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> limiter.admit(() -> {
            admitted.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        if (!admitted.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("입장 자리를 차지하지 못했습니다.");
        }
        return () -> {
            release.countDown();
            holder.join(5_000);
        };
    }

    @TestConfiguration
    public static class Config {

        @Bean
        ImageProcessingLimiter imageProcessingLimiter() {
            return new ImageProcessingLimiter(1, 0, Duration.ofSeconds(1));
        }
    }
}
