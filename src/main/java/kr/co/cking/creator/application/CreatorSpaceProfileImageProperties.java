package kr.co.cking.creator.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Creator Space 프로필 이미지를 공개적으로 읽는 CDN(또는 로컬 S3Mock) 기준 주소다. */
@ConfigurationProperties(prefix = "cking.creator.space-profile-image")
public record CreatorSpaceProfileImageProperties(String publicBaseUrl) {
}
