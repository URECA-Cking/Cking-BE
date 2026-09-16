package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CreatorApplicationServiceIntegrationTest {

    @Autowired
    private CreatorApplicationService creatorApplicationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Test
    void rejectedApplicantCanSubmitANewApplication() {
        Member applicant = memberRepository.save(new Member("신청자", null, null, MemberRole.USER));
        Member admin = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        CreatorApplication first = creatorApplicationService.apply(applicant.getMemberId()).application();

        creatorApplicationService.reject(admin.getMemberId(), first.getId(), "활동 이력이 부족합니다.");
        CreatorApplication retried = creatorApplicationService.apply(applicant.getMemberId()).application();

        assertThat(first.getStatus()).isEqualTo(CreatorApplicationStatus.REJECTED);
        assertThat(retried.getId()).isNotEqualTo(first.getId());
        assertThat(retried.getStatus()).isEqualTo(CreatorApplicationStatus.PENDING);
    }

    @Test
    void approvingApplicationCreatesOneCreatorForApplicant() {
        Member applicant = memberRepository.save(new Member("신청자", null, null, MemberRole.USER));
        Member admin = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        CreatorApplication application = creatorApplicationService.apply(applicant.getMemberId()).application();

        creatorApplicationService.approve(admin.getMemberId(), application.getId());

        assertThat(application.getStatus()).isEqualTo(CreatorApplicationStatus.APPROVED);
        assertThat(creatorRepository.findByMemberId(applicant.getMemberId()))
                .hasValueSatisfying(creator -> assertThat(creator.getName()).isEqualTo("신청자"));
    }
}
