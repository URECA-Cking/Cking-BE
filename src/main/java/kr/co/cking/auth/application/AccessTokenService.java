package kr.co.cking.auth.application;

import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.auth.application.port.AccessTokenIssuer;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.common.exception.BusinessException;
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
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_LOGIN_CODE));
        return accessTokenIssuer.issue(member.getMemberId(), member.getRole());
    }
}
