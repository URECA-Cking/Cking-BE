package kr.co.cking.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.auth.domain.OAuthAccount;
import kr.co.cking.auth.domain.OAuthProvider;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OAuthAccountRepositoryJpaTest {

    @Autowired private OAuthAccountRepository oauthAccountRepository;
    @Autowired private MemberRepository memberRepository;

    @Test
    void findsAccountByProviderAndProviderUserId() {
        Member member = memberRepository.saveAndFlush(new Member("OAuth 사용자", null, "oauth@example.com", MemberRole.USER));
        OAuthAccount saved = oauthAccountRepository.saveAndFlush(
                new OAuthAccount(member.getMemberId(), OAuthProvider.GOOGLE, "google-sub-1"));

        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-1"))
                .contains(saved);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void providerAndProviderUserIdMustBeUnique() {
        Member firstMember = memberRepository.saveAndFlush(new Member("첫 사용자", null, null, MemberRole.USER));
        Member secondMember = memberRepository.saveAndFlush(new Member("둘째 사용자", null, null, MemberRole.USER));
        oauthAccountRepository.saveAndFlush(new OAuthAccount(firstMember.getMemberId(), OAuthProvider.KAKAO, "12345"));

        assertThatThrownBy(() -> oauthAccountRepository.saveAndFlush(
                new OAuthAccount(secondMember.getMemberId(), OAuthProvider.KAKAO, "12345")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void memberReferencedByOAuthAccountCannotBeDeleted() {
        Member member = memberRepository.saveAndFlush(new Member("연결 사용자", null, null, MemberRole.USER));
        oauthAccountRepository.saveAndFlush(new OAuthAccount(member.getMemberId(), OAuthProvider.GOOGLE, "google-sub-2"));

        memberRepository.delete(member);

        assertThatThrownBy(memberRepository::flush)
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
