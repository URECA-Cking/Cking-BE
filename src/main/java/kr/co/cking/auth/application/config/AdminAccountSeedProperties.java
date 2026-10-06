package kr.co.cking.auth.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 개발·시연용 관리자 초기 계정의 환경별 설정을 받는다. */
@ConfigurationProperties(prefix = "cking.auth.admin-seed")
public record AdminAccountSeedProperties(boolean enabled, String loginId, String password) {
}
