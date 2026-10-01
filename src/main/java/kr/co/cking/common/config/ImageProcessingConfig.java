package kr.co.cking.common.config;

import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.ImageProcessingLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ImageProcessingProperties.class)
public class ImageProcessingConfig {

    @Bean
    public ImageProcessingLimiter imageProcessingLimiter(ImageProcessingProperties properties) {
        return new ImageProcessingLimiter(
                properties.maxConcurrent(), properties.maxWaiting(), properties.acquireTimeout());
    }

    @Bean
    public ImageNormalizer imageNormalizer(ImageProcessingLimiter imageProcessingLimiter) {
        return new ImageNormalizer(imageProcessingLimiter);
    }
}
