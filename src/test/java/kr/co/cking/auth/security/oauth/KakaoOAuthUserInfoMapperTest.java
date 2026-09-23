package kr.co.cking.auth.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthProvider;
import org.junit.jupiter.api.Test;

class KakaoOAuthUserInfoMapperTest {

    private final KakaoOAuthUserInfoMapper mapper = new KakaoOAuthUserInfoMapper();

    /** Kakao 표준 profile.nickname을 레거시 properties.nickname보다 우선하는지 검증한다. */
    @Test
    void mapsNumericKakaoIdStandardProfileNicknameAndEmail() {
        OAuthUserInfo userInfo = mapper.map(Map.of(
                "id", 123456789L,
                "properties", Map.of("nickname", "레거시 사용자"),
                "kakao_account", Map.of(
                        "email", "kakao@example.com",
                        "profile", Map.of("nickname", "Kakao 사용자")
                )
        ));

        assertThat(userInfo).isEqualTo(new OAuthUserInfo(
                OAuthProvider.KAKAO, "123456789", "kakao@example.com", "Kakao 사용자"));
    }

    /** 표준 프로필이 없을 때만 레거시 properties.nickname을 사용하는지 검증한다. */
    @Test
    void fallsBackToLegacyPropertiesNicknameWhenStandardProfileIsMissing() {
        OAuthUserInfo userInfo = mapper.map(Map.of(
                "id", 123456789L,
                "properties", Map.of("nickname", "레거시 사용자"),
                "kakao_account", Map.of("email", "kakao@example.com")
        ));

        assertThat(userInfo).isEqualTo(new OAuthUserInfo(
                OAuthProvider.KAKAO, "123456789", "kakao@example.com", "레거시 사용자"));
    }

    /** 이메일과 닉네임은 선택값이므로 없어도 외부 Identity 매핑을 허용하는지 검증한다. */
    @Test
    void allowsMissingOptionalProfileValues() {
        OAuthUserInfo userInfo = mapper.map(Map.of("id", "123456789"));

        assertThat(userInfo).isEqualTo(new OAuthUserInfo(OAuthProvider.KAKAO, "123456789", null, null));
    }

    /** 중첩 속성 형식이 잘못됐거나 id가 없으면 회원 연결 전에 거부하는지 검증한다. */
    @Test
    void rejectsMissingKakaoIdOrInvalidNestedAttributes() {
        assertThatThrownBy(() -> mapper.map(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(Map.of("id", 1L, "properties", "nickname")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Kakao 외부 Identity는 양의 정수만 허용하는지 검증한다. */
    @Test
    void rejectsNonIntegerOrNonPositiveKakaoIdBeforeMemberConnection() {
        assertThatThrownBy(() -> mapper.map(Map.of("id", "abc")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(Map.of("id", 1.5d)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(Map.of("id", 0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
