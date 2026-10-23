package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorApplicationRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberNickname;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
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

    @Autowired
    private CreatorApplicationRepository creatorApplicationRepository;

    @Autowired
    private CreatorSpaceRepository creatorSpaceRepository;

    @Test
    void rejectedApplicantCanSubmitANewApplication() {
        Member applicant = memberRepository.save(configuredApplicant());
        Member admin = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        CreatorApplication first = creatorApplicationService.apply(applicant.getMemberId()).application();

        creatorApplicationService.reject(admin.getMemberId(), first.getId(), "활동 이력이 부족합니다.");
        CreatorApplication retried = creatorApplicationService.apply(applicant.getMemberId()).application();

        assertThat(first.getStatus()).isEqualTo(CreatorApplicationStatus.REJECTED);
        assertThat(retried.getId()).isNotEqualTo(first.getId());
        assertThat(retried.getStatus()).isEqualTo(CreatorApplicationStatus.PENDING);
    }

    @Test
    void approvingApplicationCreatesOneCreatorAndSpaceForApplicant() {
        Member applicant = memberRepository.save(configuredApplicant());
        Member admin = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        CreatorApplication application = creatorApplicationService.apply(applicant.getMemberId()).application();

        creatorApplicationService.approve(admin.getMemberId(), application.getId());

        assertThat(application.getStatus()).isEqualTo(CreatorApplicationStatus.APPROVED);
        var creator = creatorRepository.findByMemberId(applicant.getMemberId());
        assertThat(creator).hasValueSatisfying(value -> {
            assertThat(value.getName()).isEqualTo("신청자");
            assertThat(value.getActivityName()).isEqualTo("신청자닉네임");
            assertThat(value.isActivityNameConfigured()).isTrue();
        });
        CreatorSpace space = creatorSpaceRepository.findByCreatorId(creator.orElseThrow().getCreatorId())
                .orElseThrow();
        assertThat(space.getIntroText()).isEqualTo(CreatorSpace.DEFAULT_INTRO_TEXT);
        assertThat(space.getProfileImageUrl()).isNull();
        assertThat(space.getBannerImageUrl()).isNull();
        assertThat(space.getSlug()).isEqualTo("creator-" + creator.orElseThrow().getCreatorId());
    }

    @Test
    void adminCanFilterApplicationsByStatusWithPagination() {
        Member admin = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        CreatorApplication pending = creatorApplicationRepository.save(new CreatorApplication(
                memberRepository.save(configuredApplicant("대기신청자", "대기닉네임")).getMemberId()));
        CreatorApplication rejected = new CreatorApplication(memberRepository.save(
                configuredApplicant("반려신청자", "반려닉네임")).getMemberId());
        rejected.reject(admin.getMemberId(), "거절 사유");
        creatorApplicationRepository.save(rejected);
        CreatorApplication approved = new CreatorApplication(memberRepository.save(
                configuredApplicant("승인신청자", "승인닉네임")).getMemberId());
        approved.approve(admin.getMemberId());
        creatorApplicationRepository.save(approved);

        var result = creatorApplicationService.findAllForAdmin(
                admin.getMemberId(), CreatorApplicationStatus.PENDING, PageRequest.of(0, 1));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting(CreatorApplicationService.AdminApplication::application)
                .containsExactly(pending);
        assertThat(result.hasNext()).isFalse();
    }

    private Member configuredApplicant() {
        return configuredApplicant("신청자", "신청자닉네임");
    }

    private Member configuredApplicant(String name, String nickname) {
        Member applicant = new Member(name, null, null, MemberRole.USER);
        applicant.setInitialNickname(MemberNickname.of(nickname));
        return applicant;
    }
}
