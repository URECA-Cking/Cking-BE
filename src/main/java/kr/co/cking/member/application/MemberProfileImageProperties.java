package kr.co.cking.member.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 개인 프로필 이미지를 공개적으로 읽는 CDN(또는 로컬 S3Mock) 기준 주소다. */
@ConfigurationProperties(prefix = "cking.member.profile-image")
public record MemberProfileImageProperties(String publicBaseUrl) {
}
