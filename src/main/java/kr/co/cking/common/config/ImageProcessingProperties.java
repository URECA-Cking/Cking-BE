package kr.co.cking.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cking.image-processing")
public record ImageProcessingProperties(int maxConcurrent, Duration acquireTimeout) {
}
