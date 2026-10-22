package kr.co.cking.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 애플리케이션의 정기 작업을 활성화한다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "cking.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
