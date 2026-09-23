package kr.co.cking.common.config;

import kr.co.cking.common.security.RestAccessDeniedHandler;
import kr.co.cking.common.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

/**
 * API 인증 전환을 위한 Spring Security 진입점이다.
 * 현재 API는 기존 호출자 {@code userId} 계약을 유지하므로 모두 허용하고,
 * JWT 인증 전환 작업에서 경로별 권한 규칙만 단계적으로 교체한다.
 * Swagger는 전용 Basic Auth 체인에서 보호하고, actuator는 필요한 상태 확인 경로만 공개한다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Value("${cking.docs.username}")
    private String docsUsername;

    @Value("${cking.docs.password}")
    private String docsPassword;

    /** Swagger UI와 OpenAPI JSON에만 적용할 Basic Auth 보안 체인을 구성한다. */
    @Bean
    @Order(1)
    public SecurityFilterChain docsSecurityFilterChain(HttpSecurity http, UserDetailsService docsUserDetailsService)
            throws Exception {
        http.securityMatcher("/swagger-ui/**", "/v3/api-docs/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable());

        if (isDocsAuthenticationEnabled()) {
            http.userDetailsService(docsUserDetailsService)
                    .httpBasic(basic -> basic.realmName("Cking API Docs"))
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        } else {
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        }

        return http.build();
    }

    /** 기존 API 호환과 actuator 공개 범위를 함께 보장하는 기본 보안 체인을 구성한다. */
    @Bean
    @Order(2)
    public SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/**", "/oauth2/**", "/login/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    /** 문서 Basic Auth에 사용할 단일 인메모리 계정을 등록한다. */
    @Bean
    public UserDetailsService docsUserDetailsService() {
        if (!isDocsAuthenticationEnabled()) {
            return new InMemoryUserDetailsManager();
        }

        UserDetails docsUser = User.withUsername(docsUsername)
                .password("{noop}" + docsPassword)
                .roles("DOCS")
                .build();
        return new InMemoryUserDetailsManager(docsUser);
    }

    /** 사용자 이름과 비밀번호가 모두 설정된 환경에서만 문서 Basic Auth를 활성화한다. */
    private boolean isDocsAuthenticationEnabled() {
        return StringUtils.hasText(docsUsername) && StringUtils.hasText(docsPassword);
    }
}
