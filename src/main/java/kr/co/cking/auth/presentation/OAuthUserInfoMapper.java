package kr.co.cking.auth.presentation;

import java.util.Map;

import kr.co.cking.auth.application.model.OAuthUserInfo;

/** 특정 OAuth Provider의 응답 속성을 공통 OAuthUserInfo로 정규화하는 계약이다. */
public interface OAuthUserInfoMapper {

    /** 주어진 OAuth registration ID를 이 Mapper가 처리할 수 있는지 반환한다. */
    boolean supports(String registrationId);

    /** Provider 고유 사용자 속성을 공통 OAuthUserInfo로 변환한다. */
    OAuthUserInfo resolve(Map<String, Object> attributes);
}
