package kr.co.cking.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 프론트엔드와 API의 도메인이 분리되어 있어 브라우저가 교차 출처 요청으로 처리한다.
 * Spring Security 필터 체인이 사용할 {@link CorsConfigurationSource}를 제공한다.
 * 허용 오리진은 환경별 설정값으로 두며, 비어 있으면 아무 오리진도 허용하지 않는다.
 */
@Configuration
public class CorsConfig {

    private final String[] allowedOrigins;
    private final boolean allowCredentials;

    /** 환경별 CORS 허용 origin과 credential 사용 여부를 주입한다. */
    public CorsConfig(
            @Value("${cking.cors.allowed-origins:}") String[] allowedOrigins,
            @Value("${cking.cors.allow-credentials:false}") boolean allowCredentials
    ) {
        this.allowedOrigins = allowedOrigins;
        this.allowCredentials = allowCredentials;
    }

    /** API 경로에 적용할 CORS 정책을 만들어 Spring Security와 Spring MVC에 공유한다. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins)
                .filter(StringUtils::hasText)
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION));
        configuration.setAllowCredentials(allowCredentials);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

}
