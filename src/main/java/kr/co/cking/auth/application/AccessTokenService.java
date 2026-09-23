package kr.co.cking.auth.application;

import java.util.Objects;

import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.auth.application.port.AccessTokenIssuer;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 인증 수단으로 식별한 Member에게 짧은 수명의 Access JWT를 발급한다. */
@Service
@RequiredArgsConstructor
public class AccessTokenService {

    private final MemberRepository memberRepository;
    private final AccessTokenIssuer accessTokenIssuer;

    /**
     * 존재하지 않는 Member를 호출 맥락의 인증 수단 오류로 통합해 Access JWT를 발급한다.
     *
     * <p>Login Code와 Refresh Token은 Member 누락을 서로 다른 외부 오류 계약으로 취급한다.
     */
    public AccessTokenResult issue(Long memberId, AuthErrorCode invalidCredentialError) {
        Objects.requireNonNull(memberId, "memberId는 필수입니다.");
        Objects.requireNonNull(invalidCredentialError, "invalidCredentialError는 필수입니다.");
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(invalidCredentialError));
        return accessTokenIssuer.issue(member.getMemberId(), member.getRole());
    }
}
