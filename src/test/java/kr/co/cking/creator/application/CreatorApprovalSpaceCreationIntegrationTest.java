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

/**
 * Creator 승인 시점의 Creator Space 자동 생성이 실제 DB에서 승인과 하나의 트랜잭션으로
 * 동작하는지, 활성 템플릿이 없을 때 정의한 정책대로 동작하는지, 같은 크리에이터에 대해
 * 두 번 호출해도 Space가 중복 생성되지 않는지 검증한다(이슈 #270).
 */
@SpringBootTest
class CreatorApprovalSpaceCreationIntegrationTest {

    private static final CreatorSpaceTemplateFields TEMPLATE_FIELDS = new CreatorSpaceTemplateFields(
            "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}", true, false, true, false
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
        assertThat(space.isHomeTabEnabled()).isEqualTo(TEMPLATE_FIELDS.homeTabEnabled());
        assertThat(space.isMissionsTabEnabled()).isEqualTo(TEMPLATE_FIELDS.missionsTabEnabled());
    }

    /**
     * 같은 활성 템플릿으로 여러 Creator를 연달아 승인해도 slug가 creatorId로 갈라져 충돌하지
     * 않는다. slugRule에 {creatorId} 자리표시자가 없으면 두 번째 승인부터
     * uk_creator_space_slug UNIQUE 제약 위반으로 실패했던 문제의 회귀 테스트다.
     */
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

    /**
     * Controller의 slugRule Bean Validation은 새로 생성·수정하는 템플릿만 검증한다. 그 검증이
     * 생기기 전에 저장돼 활성 상태로 남아 있는 템플릿을 흉내 내기 위해, 여기서는 Controller를
     * 거치지 않고 {@link CreatorSpaceTemplateService#create}를 직접 호출해 {creatorId}
     * 자리표시자가 없는 슬러그 규칙으로 템플릿을 만든다. 이 상태에서 승인하면 명확한
     * BusinessException으로 실패하며, Creator 생성과 신청 승인 상태도 함께 롤백돼야 한다.
     */
    @Test
    void 활성_템플릿의_slugRule에_자리표시자가_없으면_승인이_롤백된다() {
        Member admin = createMember("레거시템플릿관리자", MemberRole.ADMIN);
        Member applicant = createMember("레거시템플릿신청자", MemberRole.USER);
        CreatorSpaceTemplateFields invalidFields = new CreatorSpaceTemplateFields(
                "소개", "https://img/profile.png", "https://img/banner.png", "creator-space", true, true, true, true
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

    /** 활성 템플릿이 없으면 승인 자체가 실패하며, Creator 생성과 신청 승인 상태도 함께 롤백된다. */
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

    /** 같은 크리에이터에 대해 두 번 호출해도 Space가 하나만 존재해야 한다(승인 재시도·중복 호출 대비). */
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
