package kr.co.cking.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 프론트엔드와 API의 도메인이 분리되어 있어 브라우저가 교차 출처 요청으로 처리한다.
 * 허용 오리진은 환경별 설정값으로 두며, 비어 있으면 아무 오리진도 허용하지 않는다.
 * 로컬 개발은 Vite 프록시로 같은 출처 요청이 되므로 설정이 필요 없다.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${cking.cors.allowed-origins:}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.length == 0) {
            return;
        }

        // 인증 도입 전이라 쿠키·인증 헤더를 주고받지 않는다. allowedCredentials를 켜지 않는다.
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type")
                .maxAge(3600);
    }
}
