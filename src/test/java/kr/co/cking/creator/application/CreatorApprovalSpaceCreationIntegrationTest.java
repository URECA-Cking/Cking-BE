package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.application.dto.CreatorSpaceTemplateFields;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorApplicationRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Creator 승인과 Space 생성의 트랜잭션 경계를 검증한다. */
@SpringBootTest
class CreatorApprovalSpaceCreationIntegrationTest {

    private static final CreatorSpaceTemplateFields TEMPLATE_FIELDS = new CreatorSpaceTemplateFields(
            "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"
    );

    @Autowired
    private CreatorApplicationService creatorApplicationService;

    @Autowired
    private CreatorSpaceTemplateService creatorSpaceTemplateService;

    @Autowired
    private CreatorSpaceService creatorSpaceService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private CreatorApplicationRepository creatorApplicationRepository;

    @Autowired
    private CreatorSpaceRepository creatorSpaceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> applicationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        memberIds.forEach(memberId -> {
            jdbcTemplate.update(
                    "DELETE FROM creator_space WHERE creator_id IN (SELECT creator_id FROM creator WHERE member_id = ?)",
                    memberId
            );
            jdbcTemplate.update(
                    "DELETE FROM mission WHERE creator_id IN (SELECT creator_id FROM creator WHERE member_id = ?)",
                    memberId
            );
            jdbcTemplate.update("DELETE FROM creator WHERE member_id = ?", memberId);
            jdbcTemplate.update("DELETE FROM creator_space_template WHERE created_by = ?", memberId);
        });
        applicationIds.forEach(applicationId -> jdbcTemplate.update(
                "DELETE FROM creator_application WHERE id = ?", applicationId));
        memberIds.forEach(memberId -> jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId));
    }

    @Test
    void 승인_시_활성_템플릿을_복사해_스페이스를_생성한다() {
        Member admin = createMember("스페이스관리자", MemberRole.ADMIN);
        Member applicant = createMember("스페이스신청자", MemberRole.USER);
        Long templateId = creatorSpaceTemplateService.create(admin.getMemberId(), TEMPLATE_FIELDS).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);
        CreatorApplication application = createApplication(applicant.getMemberId());

        creatorApplicationService.approve(admin.getMemberId(), application.getId());

        Creator creator = creatorRepository.findByMemberId(applicant.getMemberId()).orElseThrow();
        CreatorSpace space = creatorSpaceRepository.findByCreatorId(creator.getCreatorId()).orElseThrow();
        assertThat(space.getIntroText()).isEqualTo(TEMPLATE_FIELDS.introText());
        assertThat(space.getSlug()).isEqualTo("creator-" + creator.getCreatorId());
    }

    @Test
    void 같은_템플릿으로_여러_크리에이터를_승인해도_slug가_충돌하지_않는다() {
        Member admin = createMember("복수승인관리자", MemberRole.ADMIN);
        Member firstApplicant = createMember("복수승인신청자1", MemberRole.USER);
        Member secondApplicant = createMember("복수승인신청자2", MemberRole.USER);
        Long templateId = creatorSpaceTemplateService.create(admin.getMemberId(), TEMPLATE_FIELDS).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);
        CreatorApplication firstApplication = createApplication(firstApplicant.getMemberId());
        CreatorApplication secondApplication = createApplication(secondApplicant.getMemberId());

        creatorApplicationService.approve(admin.getMemberId(), firstApplication.getId());
        creatorApplicationService.approve(admin.getMemberId(), secondApplication.getId());

        Creator firstCreator = creatorRepository.findByMemberId(firstApplicant.getMemberId()).orElseThrow();
        Creator secondCreator = creatorRepository.findByMemberId(secondApplicant.getMemberId()).orElseThrow();
        CreatorSpace firstSpace = creatorSpaceRepository.findByCreatorId(firstCreator.getCreatorId()).orElseThrow();
        CreatorSpace secondSpace = creatorSpaceRepository.findByCreatorId(secondCreator.getCreatorId()).orElseThrow();
        assertThat(firstSpace.getSlug()).isNotEqualTo(secondSpace.getSlug());
    }

    @Test
    void 템플릿_slugRule을_바꾼_뒤_순차_승인해도_slug가_충돌하지_않는다() {
        Member admin = createMember("템플릿변경관리자", MemberRole.ADMIN);
        Member firstApplicant = createMember("템플릿변경신청자1", MemberRole.USER);
        Member secondApplicant = createMember("템플릿변경신청자2", MemberRole.USER);
        Member thirdApplicant = createMember("템플릿변경신청자3", MemberRole.USER);
        Long templateId = creatorSpaceTemplateService.create(admin.getMemberId(), TEMPLATE_FIELDS).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);

        creatorApplicationService.approve(admin.getMemberId(), createApplication(firstApplicant.getMemberId()).getId());
        creatorSpaceTemplateService.update(admin.getMemberId(), templateId, withSlugRule("creator-1-{creatorId}"));
        creatorApplicationService.approve(admin.getMemberId(), createApplication(secondApplicant.getMemberId()).getId());
        creatorSpaceTemplateService.update(admin.getMemberId(), templateId, withSlugRule("c{creatorId}"));
        creatorApplicationService.approve(admin.getMemberId(), createApplication(thirdApplicant.getMemberId()).getId());

        Long firstCreatorId = creatorRepository.findByMemberId(firstApplicant.getMemberId()).orElseThrow().getCreatorId();
        Long secondCreatorId = creatorRepository.findByMemberId(secondApplicant.getMemberId()).orElseThrow().getCreatorId();
        Long thirdCreatorId = creatorRepository.findByMemberId(thirdApplicant.getMemberId()).orElseThrow().getCreatorId();
        assertThat(creatorSpaceRepository.findByCreatorId(firstCreatorId).orElseThrow().getSlug())
                .isEqualTo("creator-" + firstCreatorId);
        assertThat(creatorSpaceRepository.findByCreatorId(secondCreatorId).orElseThrow().getSlug())
                .isEqualTo("creator-1-" + secondCreatorId);
        assertThat(creatorSpaceRepository.findByCreatorId(thirdCreatorId).orElseThrow().getSlug())
                .isEqualTo("c" + thirdCreatorId);
    }

    @Test
    void 자리표시자가_끝에_오지_않는_레거시_slugRule이면_승인이_롤백된다() {
        Member admin = createMember("레거시접미사관리자", MemberRole.ADMIN);
        Member applicant = createMember("레거시접미사신청자", MemberRole.USER);
        Long templateId = creatorSpaceTemplateService.create(
                admin.getMemberId(), withSlugRule("creator-{creatorId}0")).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);
        CreatorApplication application = createApplication(applicant.getMemberId());

        assertThatThrownBy(() -> creatorApplicationService.approve(admin.getMemberId(), application.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE);
        assertThat(creatorRepository.existsByMemberId(applicant.getMemberId())).isFalse();
    }

    // 기존 데이터처럼 검증을 거치지 않은 템플릿을 서비스에서 직접 만든다.
    @Test
    void 활성_템플릿의_slugRule에_자리표시자가_없으면_승인이_롤백된다() {
        Member admin = createMember("레거시템플릿관리자", MemberRole.ADMIN);
        Member applicant = createMember("레거시템플릿신청자", MemberRole.USER);
        CreatorSpaceTemplateFields invalidFields = new CreatorSpaceTemplateFields(
                "소개", "https://img/profile.png", "https://img/banner.png", "creator-space"
        );
        Long templateId = creatorSpaceTemplateService.create(admin.getMemberId(), invalidFields).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);
        CreatorApplication application = createApplication(applicant.getMemberId());

        assertThatThrownBy(() -> creatorApplicationService.approve(admin.getMemberId(), application.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE);

        assertThat(creatorRepository.existsByMemberId(applicant.getMemberId())).isFalse();
        assertThat(creatorApplicationRepository.findById(application.getId()).orElseThrow().getStatus())
                .isEqualTo(CreatorApplicationStatus.PENDING);
    }

    @Test
    void 활성_템플릿이_없으면_승인_자체가_롤백된다() {
        Member admin = createMember("템플릿없는관리자", MemberRole.ADMIN);
        Member applicant = createMember("템플릿없는신청자", MemberRole.USER);
        CreatorApplication application = createApplication(applicant.getMemberId());

        assertThatThrownBy(() -> creatorApplicationService.approve(admin.getMemberId(), application.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.NO_ACTIVE_SPACE_TEMPLATE);

        assertThat(creatorRepository.existsByMemberId(applicant.getMemberId())).isFalse();
        assertThat(creatorApplicationRepository.findById(application.getId()).orElseThrow().getStatus())
                .isEqualTo(CreatorApplicationStatus.PENDING);
    }

    @Test
    void 동일_크리에이터에_대해_스페이스가_두_번_생성되지_않는다() {
        Member admin = createMember("멱등관리자", MemberRole.ADMIN);
        Member creatorMember = createMember("멱등크리에이터", MemberRole.USER);
        Creator creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "멱등크리에이터"));
        Long templateId = creatorSpaceTemplateService.create(admin.getMemberId(), TEMPLATE_FIELDS).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);

        CreatorSpace first = creatorSpaceService.createFromActiveTemplateIfAbsent(creator.getCreatorId());
        CreatorSpace second = creatorSpaceService.createFromActiveTemplateIfAbsent(creator.getCreatorId());

        assertThat(second.getSpaceId()).isEqualTo(first.getSpaceId());
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM creator_space WHERE creator_id = ?", Long.class, creator.getCreatorId());
        assertThat(count).isEqualTo(1L);
    }

    private CreatorSpaceTemplateFields withSlugRule(String slugRule) {
        return new CreatorSpaceTemplateFields(
                TEMPLATE_FIELDS.introText(), TEMPLATE_FIELDS.profileImageUrl(), TEMPLATE_FIELDS.bannerImageUrl(),
                slugRule
        );
    }

    private Member createMember(String name, MemberRole role) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, role));
        memberIds.add(member.getMemberId());
        return member;
    }

    private CreatorApplication createApplication(Long applicantId) {
        CreatorApplication application = creatorApplicationRepository.saveAndFlush(new CreatorApplication(applicantId));
        applicationIds.add(application.getId());
        return application;
    }
}
