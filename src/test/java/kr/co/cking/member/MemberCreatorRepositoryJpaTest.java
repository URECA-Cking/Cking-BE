package kr.co.cking.member;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MemberCreatorRepositoryJpaTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Test
    void memberRepositoryFindsMemberAndChecksExistence() {
        Member saved = memberRepository.saveAndFlush(
                new Member("태연", null, "taeyeon@example.com", MemberRole.USER));

        assertThat(memberRepository.findById(saved.getMemberId())).contains(saved);
        assertThat(memberRepository.existsById(saved.getMemberId())).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void creatorRepositoryFindsCreatorByMemberIdAndChecksExistence() {
        Member member = memberRepository.saveAndFlush(
                new Member("태연", null, "taeyeon@example.com", MemberRole.USER));
        Creator saved = creatorRepository.saveAndFlush(
                new Creator(member.getMemberId(), "태연 Creator"));

        assertThat(creatorRepository.findById(saved.getCreatorId())).contains(saved);
        assertThat(creatorRepository.findByMemberId(member.getMemberId())).contains(saved);
        assertThat(creatorRepository.existsByMemberId(member.getMemberId())).isTrue();
    }

    @Test
    void creatorMemberIdCannotBeRegisteredTwice() {
        Member member = memberRepository.saveAndFlush(
                new Member("태연", null, "taeyeon@example.com", MemberRole.USER));
        creatorRepository.saveAndFlush(new Creator(member.getMemberId(), "태연 Creator"));

        assertThatThrownBy(() -> creatorRepository.saveAndFlush(
                new Creator(member.getMemberId(), "Duplicate Creator")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
