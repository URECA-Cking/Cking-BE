package kr.co.cking.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 시각 판정(displayStatus, 마감 등)을 테스트에서 고정 시각으로 갈아끼울 수 있도록
 * {@code Instant.now()}를 직접 쓰지 않고 주입받는 {@link Clock}으로 통일한다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
