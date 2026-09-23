package kr.co.cking.auth.application.port;

import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.member.domain.MemberRole;

/** Member 식별자와 역할로 API Access Token을 발급하는 인증 경계 포트다. */
public interface AccessTokenIssuer {

    /** 발급한 Access Token과 클라이언트가 사용할 메타데이터를 반환한다. */
    AccessTokenResult issue(Long memberId, MemberRole role);
}
