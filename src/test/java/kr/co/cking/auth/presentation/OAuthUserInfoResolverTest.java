package kr.co.cking.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/** Provider 응답을 OAuthLoginService 입력 모델로 정규화하는지 검증한다. */
class OAuthUserInfoResolverTest {

    private final OAuthUserInfoResolver resolver = new OAuthUserInfoResolver();

    /** Google의 sub와 프로필 속성을 공통 모델에 옮기는지 검증한다. */
    @Test
    void Google_속성을_OAuthUserInfo로_정규화한다() {
        OAuthUserInfo userInfo = resolver.resolve(authentication("google", Map.of(
                "sub", "google-sub",
                "email", "google@example.com",
                "name", "구글 사용자"
        )));

        assertThat(userInfo).isEqualTo(new OAuthUserInfo(
                OAuthProvider.GOOGLE, "google-sub", "google@example.com", "구글 사용자"));
    }

    /** Kakao 표준 profile.nickname을 레거시 properties.nickname보다 우선하는지 검증한다. */
    @Test
    void Kakao_표준_프로필_닉네임을_우선해_OAuthUserInfo로_정규화한다() {
        OAuthUserInfo userInfo = resolver.resolve(authentication("kakao", Map.of(
                "id", 12345L,
                "kakao_account", Map.of(
                        "email", "kakao@example.com",
                        "profile", Map.of("nickname", "표준 카카오 사용자")
                ),
                "properties", Map.of("nickname", "레거시 카카오 사용자")
        )));

        assertThat(userInfo).isEqualTo(new OAuthUserInfo(
                OAuthProvider.KAKAO, "12345", "kakao@example.com", "표준 카카오 사용자"));
    }

    /** Kakao 표준 프로필이 없을 때만 레거시 properties.nickname을 사용하는지 검증한다. */
    @Test
    void Kakao_표준_프로필이_없으면_레거시_닉네임을_사용한다() {
        OAuthUserInfo userInfo = resolver.resolve(authentication("kakao", Map.of(
                "id", 12345L,
                "kakao_account", Map.of("email", "kakao@example.com"),
                "properties", Map.of("nickname", "레거시 카카오 사용자")
        )));

        assertThat(userInfo).isEqualTo(new OAuthUserInfo(
                OAuthProvider.KAKAO, "12345", "kakao@example.com", "레거시 카카오 사용자"));
    }

    /** 테스트용 OAuth2 인증 토큰을 만든다. */
    private OAuth2AuthenticationToken authentication(String registrationId, Map<String, Object> attributes) {
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, attributes.keySet().iterator().next());
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), registrationId);
    }
}
