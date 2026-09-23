package kr.co.cking.auth.presentation;

import java.util.List;

import kr.co.cking.auth.application.model.OAuthUserInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/** OAuth registration ID에 맞는 Provider Mapper를 선택해 사용자 정보 정규화를 위임한다. */
@Component
@RequiredArgsConstructor
public class OAuthUserInfoResolver {

    private final List<OAuthUserInfoMapper> oauthUserInfoMappers;

    /** 인증 토큰의 registration ID를 처리할 Mapper에 Provider 속성 해석을 위임한다. */
    public OAuthUserInfo resolve(OAuth2AuthenticationToken authentication) {
        String registrationId = authentication.getAuthorizedClientRegistrationId();
        return oauthUserInfoMappers.stream()
                .filter(mapper -> mapper.supports(registrationId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 OAuth Provider입니다."))
                .resolve(authentication.getPrincipal().getAttributes());
    }
}
