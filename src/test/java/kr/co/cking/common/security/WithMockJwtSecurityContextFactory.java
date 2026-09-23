package kr.co.cking.common.security;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.List;

/** 테스트용 JWT subject를 SecurityContext에 인증 정보로 설정한다. */
public class WithMockJwtSecurityContextFactory implements WithSecurityContextFactory<WithMockJwt> {

    /** 지정한 Member ID를 subject로 하는 JWT 인증 컨텍스트를 만든다. */
    @Override
    public SecurityContext createSecurityContext(WithMockJwt annotation) {
        Jwt token = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("sub", annotation.memberId())
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(token, List.of()));
        return context;
    }
}
