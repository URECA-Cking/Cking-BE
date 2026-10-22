package kr.co.cking.common.security;

import java.util.List;
import java.util.Set;

import kr.co.cking.member.domain.MemberRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** Access JWT의 Cking role Claim을 Spring Security 권한으로 변환한다. */
@Configuration
public class JwtAuthenticationConverterConfig {

    /** JWT의 USER·ADMIN role을 각각 ROLE_USER·ROLE_ADMIN 권한으로 바꾸는 Converter를 제공한다. */
    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null || !Set.of(MemberRole.USER.name(), MemberRole.ADMIN.name()).contains(role)) {
                return List.of();
            }
            return List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter::convert;
    }
}
