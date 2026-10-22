package kr.co.cking.common.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** 추천 적재 배치 API Key 인증 결과다. 회원이 아니라서 {@code RECOMMENDATION_WRITE} 권한만 가진다. */
final class RecommendationApiKeyAuthentication extends AbstractAuthenticationToken {

    static final String AUTHORITY = "RECOMMENDATION_WRITE";

    RecommendationApiKeyAuthentication() {
        super(List.of(new SimpleGrantedAuthority(AUTHORITY)));
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return "recommendation-batch";
    }
}
