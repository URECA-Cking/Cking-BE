package kr.co.cking.common.config;

import kr.co.cking.common.security.RestAccessDeniedHandler;
import kr.co.cking.common.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API 인증 전환을 위한 Spring Security 진입점이다.
 * 현재 API는 기존 호출자 {@code userId} 계약을 유지하므로 모두 허용하고,
 * JWT 인증 전환 작업에서 경로별 권한 규칙만 단계적으로 교체한다.
 * Swagger 경로는 이 체인의 대상에서 제외해 서블릿 Basic Auth 필터가 단독으로 보호한다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    /** 기존 API 호환을 보장하는 보안 필터 체인을 구성한다. */
    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/**", "/oauth2/**", "/login/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }
}
