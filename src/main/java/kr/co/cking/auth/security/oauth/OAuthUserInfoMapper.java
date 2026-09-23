package kr.co.cking.auth.security.oauth;

import java.util.Map;
import kr.co.cking.auth.application.model.OAuthUserInfo;

/** Provider 원본 attributes를 Cking의 공통 OAuth 사용자 정보로 정규화한다. */
public interface OAuthUserInfoMapper {

    String registrationId();

    OAuthUserInfo map(Map<String, Object> attributes);
}
