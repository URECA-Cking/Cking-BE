package kr.co.cking.common.config;

import kr.co.cking.common.security.RestAccessDeniedHandler;
import kr.co.cking.common.security.RestAuthenticationEntryPoint;
import kr.co.cking.auth.presentation.OAuth2LoginFailureHandler;
import kr.co.cking.auth.presentation.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

/**
 * API 인증 전환을 위한 Spring Security 진입점이다.
 * Resource Server는 Bearer JWT를 검증하고, 현재 API는 기존 호출자 {@code userId} 계약을 유지하므로
 * 모두 허용한다. 업무 API 전환 작업에서 경로별 인증 규칙을 단계적으로 교체한다.
 * Swagger는 전용 Basic Auth 체인에서 보호하고, actuator는 필요한 상태 확인 경로만 공개한다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final OAuth2LoginSuccessHandler oauth2LoginSuccessHandler;
    private final OAuth2LoginFailureHandler oauth2LoginFailureHandler;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider;
    private final Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter;

    @Value("${cking.docs.username:}")
    private String docsUsername;

    @Value("${cking.docs.password:}")
    private String docsPassword;

    /** Swagger UI와 OpenAPI JSON에만 적용할 Basic Auth 보안 체인을 구성한다. */
    @Bean
    @Order(1)
    public SecurityFilterChain docsSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable());

        if (isDocsAuthenticationEnabled()) {
            http.userDetailsService(createDocsUserDetailsService())
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
        configureOAuth2LoginIfRegistered(http);
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/**", "/oauth2/**", "/login/**").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(applicationBearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    /**
     * Refresh Cookie 흐름은 Access JWT와 독립적으로 동작해야 한다.
     * 만료된 Access JWT가 자동으로 첨부되어도 refresh/logout Controller까지 도달하게 한다.
     */
    private BearerTokenResolver applicationBearerTokenResolver() {
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        return request -> isRefreshCookieEndpoint(request.getRequestURI(), request.getContextPath())
                ? null
                : defaultResolver.resolve(request);
    }

    private boolean isRefreshCookieEndpoint(String requestUri, String contextPath) {
        String path = requestUri.substring(contextPath.length());
        return path.equals("/api/auth/refresh") || path.equals("/api/auth/logout");
    }

    /** OAuth Client 등록이 있는 환경에서만 로그인 성공·실패 Handler를 Security 체인에 연결한다. */
    private void configureOAuth2LoginIfRegistered(HttpSecurity http) throws Exception {
        if (clientRegistrationRepositoryProvider.getIfAvailable() != null) {
            http.oauth2Login(oauth2 -> oauth2
                    .successHandler(oauth2LoginSuccessHandler)
                    .failureHandler(oauth2LoginFailureHandler));
        }
    }

    /** 문서 보안 체인에서만 사용할 단일 인메모리 계정을 만든다. */
    private InMemoryUserDetailsManager createDocsUserDetailsService() {
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
