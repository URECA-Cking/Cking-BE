package kr.co.cking.auth.application.config;

import kr.co.cking.auth.application.AdminAccountInitializationService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 개발·시연 프로필에서만 관리자 초기 계정 생성을 연결한다. */
@Configuration
@Profile({"local", "dev"})
@EnableConfigurationProperties(AdminAccountSeedProperties.class)
public class AdminAccountSeedConfiguration {

    /** 시딩이 활성화된 환경에서 애플리케이션 기동 후 관리자 계정 생성을 실행한다. */
    @Bean
    ApplicationRunner adminAccountSeedRunner(
            AdminAccountInitializationService initializationService,
            AdminAccountSeedProperties properties
    ) {
        return arguments -> {
            if (properties.enabled()) {
                initializationService.initialize(properties.loginId(), properties.password());
            }
        };
    }
}
