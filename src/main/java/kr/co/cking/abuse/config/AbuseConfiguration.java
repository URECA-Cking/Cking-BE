package kr.co.cking.abuse.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 구현체와 무관하게 Abuse Detection 설정 계약을 애플리케이션에 등록한다. */
@Configuration
@EnableConfigurationProperties(AbuseProperties.class)
public class AbuseConfiguration {
}
