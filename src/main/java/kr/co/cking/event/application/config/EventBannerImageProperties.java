package kr.co.cking.event.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Event 배너 이미지를 공개적으로 읽는 CDN(또는 로컬 S3Mock) 기준 주소다. */
@ConfigurationProperties(prefix = "cking.event.banner-image")
public record EventBannerImageProperties(String publicBaseUrl) {
}
