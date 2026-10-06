package kr.co.cking.common.config;

import kr.co.cking.common.security.RecommendationApiKeyAuthenticationFilter;
import kr.co.cking.common.security.RestAccessDeniedHandler;
import kr.co.cking.common.security.RestAuthenticationEntryPoint;
import kr.co.cking.auth.presentation.OAuth2LoginFailureHandler;
import kr.co.cking.auth.presentation.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.core.annotation.Order;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * API 인증 전환을 위한 Spring Security 진입점이다.
 * Resource Server는 Bearer JWT를 검증하고, 인증 전환한 업무 API는 경로별로 인증을 요구한다.
 * Swagger는 전용 Basic Auth 체인에서 보호한다. actuator는 호스트에 열지 않는 관리 포트에만 있어 인증 없이 허용하고,
 * 서비스 포트에는 ALB 헬스체크용 /readyz만 둔다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final RequestMatcher ACCESS_TOKEN_ISSUANCE_ENDPOINTS = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/token"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/refresh"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/logout"));

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

    /** 추천 적재 API Key의 SHA-256 hex 목록. 비어 있으면 키 인증은 항상 실패한다. 교체 중에는 새 키·이전 키를 함께 둔다. */
    @Value("${cking.recommendation.api-key-hashes:}")
    private List<String> recommendationApiKeyHashes;

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

    /** 인증 전환 API와 기존 공개 API의 접근 범위를 함께 보장하는 기본 보안 체인을 구성한다. */
    @Bean
    @Order(2)
    public SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
        configureOAuth2LoginIfRegistered(http);
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/readyz", "/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(RecommendationApiKeyAuthenticationFilter.WRITE_ENDPOINTS)
                        .hasAnyAuthority("ROLE_ADMIN", "RECOMMENDATION_WRITE")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/winners/*/history").authenticated()
                        .requestMatchers(
                                "/api/creators/*/missions",
                                "/api/creators/*/missions/*/complete",
                                "/api/creators/*/missions/*/subscription-verifications",
                                "/api/creators/*/missions/*/subscription-verifications/me/latest",
                                "/api/subscription-verifications/*",
                                "/api/missions",
                                "/api/missions/*/complete",
                                "/api/creators/*/tickets",
                                "/api/creators/*/tickets/history",
                                "/api/creators/*/follow",
                                "/api/me/follows",
                                "/api/me/creator-recommendations",
                                "/api/tickets/common",
                                "/api/tickets/common/history",
                                "/api/events/*",
                                "/api/events/*/entries",
                                "/api/events/*/entries/me",
                                "/api/events/*/entry-status",
                                "/api/me/notifications",
                                "/api/me/notifications/**",
                                "/api/me/winners",
                                "/api/me/winners/**",
                                "/api/me/calendar/**",
                                "/api/creator/applications",
                                "/api/creator/applications/me",
                                "/api/creator/space",
                                "/api/creator/calendar/**",
                                "/api/creator/posts",
                                "/api/creator/posts/**",
                                "/api/creator/youtube-channel",
                                "/api/creator/space/slug"
                        ).authenticated()
                        .requestMatchers("/api/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/creators/*/posts/*/comments").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/creators/*/posts/*/comments/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/creators/*/posts/*/comments/*").authenticated()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/creators/*/calendar/schedules",
                                "/api/creators/*/calendar/schedules/*",
                                "/api/creators/*/posts",
                                "/api/creators/*/posts/*",
                                "/api/creators/*/posts/*/comments",
                                "/api/creators/*/youtube-channel"
                        ).permitAll()
                        .requestMatchers("/api/**", "/oauth2/**", "/login/**").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(applicationBearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(
                        new RecommendationApiKeyAuthenticationFilter(
                                recommendationApiKeyHashes, authenticationEntryPoint),
                        BearerTokenAuthenticationFilter.class)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    /**
     * Login Code·Refresh Cookie 인증 흐름은 기존 Access JWT와 독립적으로 동작해야 한다.
     * 만료된 Access JWT가 자동으로 첨부되어도 Auth Controller까지 도달하게 한다.
     */
    private BearerTokenResolver applicationBearerTokenResolver() {
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        return request -> ACCESS_TOKEN_ISSUANCE_ENDPOINTS.matches(request)
                || RecommendationApiKeyAuthenticationFilter.carriesApiKey(request)
                ? null
                : defaultResolver.resolve(request);
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
