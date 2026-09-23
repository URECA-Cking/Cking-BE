package kr.co.cking.auth.application;

import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthAccount;
import kr.co.cking.auth.repository.OAuthAccountRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 최초 OAuth 로그인에서 Member와 OAuthAccount를 원자적으로 생성한다. */
@Service
@RequiredArgsConstructor
class OAuthAccountCreationService {

    private final MemberRepository memberRepository;
    private final OAuthAccountRepository oauthAccountRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long create(OAuthUserInfo userInfo, String normalizedName) {
        Member member = memberRepository.save(new Member(
                normalizedName,
                null,
                userInfo.email(),
                MemberRole.USER
        ));
        oauthAccountRepository.saveAndFlush(new OAuthAccount(
                member.getMemberId(),
                userInfo.provider(),
                userInfo.providerUserId()
        ));
        return member.getMemberId();
    }
}
