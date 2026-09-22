package kr.co.cking.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * API 문서 인증 필터를 등록한다.
 * 아이디·비밀번호가 비어 있으면 등록하지 않는다. 로컬과 CI는 인증 없이 문서를 본다.
 */
@Configuration
public class DocsBasicAuthConfig {

    @Bean
    public FilterRegistrationBean<DocsBasicAuthFilter> docsBasicAuthFilter(
            @Value("${cking.docs.username}") String username,
            @Value("${cking.docs.password}") String password
    ) {
        FilterRegistrationBean<DocsBasicAuthFilter> registration =
                new FilterRegistrationBean<>(new DocsBasicAuthFilter(username, password));
        registration.setEnabled(StringUtils.hasText(username) && StringUtils.hasText(password));
        registration.addUrlPatterns("/swagger-ui/*", "/v3/api-docs", "/v3/api-docs/*");
        return registration;
    }
}
