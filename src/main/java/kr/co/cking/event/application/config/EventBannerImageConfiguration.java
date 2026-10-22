package kr.co.cking.event.application.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EventBannerImageProperties.class)
public class EventBannerImageConfiguration {
}
