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
    public ImageNormalizer imageNormalizer(ImageProcessingProperties properties) {
        return new ImageNormalizer(
                new ImageProcessingLimiter(properties.maxConcurrent(), properties.acquireTimeout()));
    }
}
