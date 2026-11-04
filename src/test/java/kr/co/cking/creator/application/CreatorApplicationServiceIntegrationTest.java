package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        CreatorApplication application = creatorApplicationService.apply(applicant.getMemberId(),
                kr.co.cking.creator.CreatorApplicationTestForm.form("신청자닉네임")).application();

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
    void legacyPendingNeedsRevisionBeforeApproval() {
        Member applicant = memberRepository.save(configuredApplicant());
        Member admin = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        CreatorApplication legacy = creatorApplicationService.apply(applicant.getMemberId()).application();

        assertThatThrownBy(() -> creatorApplicationService.approve(admin.getMemberId(), legacy.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.APPLICATION_FORM_REQUIRED);
        assertThat(legacy.getStatus()).isEqualTo(CreatorApplicationStatus.PENDING);
        assertThat(creatorRepository.existsByMemberId(applicant.getMemberId())).isFalse();
    }

    @Test
    void revisionDraftAndSubmittedVersionsRemainSeparate() {
        Member applicant = memberRepository.save(configuredApplicant());
        Member admin = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        var original = kr.co.cking.creator.CreatorApplicationTestForm.form("신청자닉네임");
        CreatorApplication application = creatorApplicationService.apply(applicant.getMemberId(), original).application();

        creatorApplicationService.requestRevision(admin.getMemberId(), application.getId(), "소개를 보완해 주세요.");
        assertThat(application.getStatus()).isEqualTo(CreatorApplicationStatus.NEEDS_REVISION);
        assertThat(application.getReviewedAt()).isNull();

        var revised = kr.co.cking.creator.CreatorApplicationTestForm.form("새활동명");
        creatorApplicationService.updateDraft(applicant.getMemberId(), application.getId(), revised);
        assertThat(application.getSubmittedForm()).isEqualTo(original);
        assertThat(application.getDraftForm()).isEqualTo(revised);
        assertThatThrownBy(() -> creatorApplicationService.approve(admin.getMemberId(), application.getId()))
                .isInstanceOf(BusinessException.class);

        creatorApplicationService.resubmit(applicant.getMemberId(), application.getId());
        assertThat(application.getStatus()).isEqualTo(CreatorApplicationStatus.PENDING);
        assertThat(application.getSubmissionVersion()).isEqualTo(2);
        var detail = creatorApplicationService.findDetail(admin.getMemberId(), application.getId(), true);
        assertThat(detail.submissions()).hasSize(2);
        assertThat(detail.submissions().get(0).getForm()).isEqualTo(original);
        assertThat(detail.submissions().get(1).getForm()).isEqualTo(revised);
        assertThat(detail.reviews()).extracting(item -> item.getAction())
                .containsExactly("NEEDS_REVISION", "RESUBMITTED");
    }

    @Test
    void invalidCategoryAndNonHttpsEvidenceAreRejected() {
        Member applicant = memberRepository.save(configuredApplicant());
        var valid = kr.co.cking.creator.CreatorApplicationTestForm.form("신청자닉네임");
        var badCategory = new kr.co.cking.creator.domain.CreatorApplicationForm(valid.preferredActivityName(), null,
                java.util.List.of("UNKNOWN"), valid.introduction(), valid.evidenceUrls(), valid.activityPlan(),
                valid.platforms(), true);
        var badUrl = new kr.co.cking.creator.domain.CreatorApplicationForm(valid.preferredActivityName(), null,
                valid.categoryCodes(), valid.introduction(), java.util.List.of("http://example.com/proof"),
                valid.activityPlan(), valid.platforms(), true);

        for (var form : java.util.List.of(badCategory, badUrl)) {
            assertThatThrownBy(() -> creatorApplicationService.apply(applicant.getMemberId(), form))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getErrorCode())
                    .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    @Test
    void sameApplicationIsIdempotentButDifferentBodyConflicts() {
        Member applicant = memberRepository.save(configuredApplicant());
        var form = kr.co.cking.creator.CreatorApplicationTestForm.form("신청자닉네임");
        CreatorApplication original = creatorApplicationService.apply(applicant.getMemberId(), form).application();

        var replay = creatorApplicationService.apply(applicant.getMemberId(), form);
        assertThat(replay.created()).isFalse();
        assertThat(replay.application().getId()).isEqualTo(original.getId());
        var different = kr.co.cking.creator.CreatorApplicationTestForm.form("다른활동명");
        assertThatThrownBy(() -> creatorApplicationService.apply(applicant.getMemberId(), different))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.APPLICATION_CONTENT_CONFLICT);
    }

    @Test
    void anotherMemberCannotReadOrEditRevisionDraft() {
        Member applicant = memberRepository.save(configuredApplicant());
        Member other = memberRepository.save(configuredApplicant("다른회원", "다른회원닉네임"));
        Member admin = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        CreatorApplication application = creatorApplicationService.apply(applicant.getMemberId(),
                kr.co.cking.creator.CreatorApplicationTestForm.form("신청자닉네임")).application();
        creatorApplicationService.requestRevision(admin.getMemberId(), application.getId(), "보완 필요");

        assertThatThrownBy(() -> creatorApplicationService.findDetail(other.getMemberId(), application.getId(), false))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> creatorApplicationService.updateDraft(other.getMemberId(), application.getId(),
                kr.co.cking.creator.CreatorApplicationTestForm.form("다른활동명")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
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
                configuredApplicant("승인신청자", "승인닉네임")).getMemberId(),
                kr.co.cking.creator.CreatorApplicationTestForm.form("승인닉네임"));
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
