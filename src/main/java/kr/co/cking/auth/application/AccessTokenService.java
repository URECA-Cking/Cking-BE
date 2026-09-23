package kr.co.cking.auth.application;

import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.auth.application.port.AccessTokenIssuer;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Login Code로 식별한 Member에게 짧은 수명의 Access JWT를 발급한다. */
@Service
@RequiredArgsConstructor
public class AccessTokenService {

    private final MemberRepository memberRepository;
    private final AccessTokenIssuer accessTokenIssuer;

    /** 존재하는 Member의 역할을 Claim에 담아 Access JWT와 만료 초를 반환한다. */
    public AccessTokenResult issue(Long memberId) {
        return issue(memberId, AuthErrorCode.INVALID_LOGIN_CODE);
    }

    /**
     * 존재하지 않는 Member를 호출 맥락의 인증 수단 오류로 통합해 Access JWT를 발급한다.
     *
     * <p>Login Code와 Refresh Token은 Member 누락을 서로 다른 외부 오류 계약으로 취급한다.
     */
    public AccessTokenResult issue(Long memberId, ErrorCode invalidCredentialError) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(invalidCredentialError));
        return accessTokenIssuer.issue(member.getMemberId(), member.getRole());
    }
}
