package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.application.dto.CreatorSpaceTemplateFields;
import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorApplicationRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceTemplateRepository;
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

/** 승인으로 만든 Space의 조회·수정과 커스텀 slug 변경을 실제 DB로 검증한다(이슈 #286, #290). */
@SpringBootTest
class CreatorSpaceProfileIntegrationTest {

    private static final CreatorSpaceTemplateFields TEMPLATE_FIELDS = new CreatorSpaceTemplateFields(
            "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"
    );

    @Autowired
    private CreatorSpaceProfileService profileService;

    @Autowired
    private CreatorApplicationService creatorApplicationService;

    @Autowired
    private CreatorSpaceService creatorSpaceService;

    @Autowired
    private CreatorSpaceTemplateService creatorSpaceTemplateService;

    @Autowired
    private CreatorSpaceTemplateRepository templateRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private CreatorApplicationRepository creatorApplicationRepository;

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
    void 본인_수정은_자기_Space만_바꾸고_템플릿과_다른_Space는_그대로_둔다() {
        Member admin = createMember("프로필관리자", MemberRole.ADMIN);
        Member owner = createMember("프로필수정자", MemberRole.USER);
        Member other = createMember("다른크리에이터", MemberRole.USER);
        Long templateId = creatorSpaceTemplateService.create(admin.getMemberId(), TEMPLATE_FIELDS).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);
        approve(admin, owner);
        approve(admin, other);
        Creator ownerCreator = creatorRepository.findByMemberId(owner.getMemberId()).orElseThrow();
        Creator otherCreator = creatorRepository.findByMemberId(other.getMemberId()).orElseThrow();

        profileService.updateMine(owner.getMemberId(), new CreatorSpaceProfileFields(
                "새 소개", "https://img/p2.png", "https://img/b2.png"
        ));

        CreatorSpaceView updated = profileService.findByCreatorId(ownerCreator.getCreatorId());
        assertThat(updated.space().getIntroText()).isEqualTo("새 소개");
        assertThat(updated.space().getSlug()).isEqualTo("creator-" + ownerCreator.getCreatorId());
        assertThat(updated.creatorName()).isEqualTo(ownerCreator.getName());

        CreatorSpaceView untouched = profileService.findByCreatorId(otherCreator.getCreatorId());
        assertThat(untouched.space().getIntroText()).isEqualTo(TEMPLATE_FIELDS.introText());

        CreatorSpaceTemplate template = templateRepository.findById(templateId).orElseThrow();
        assertThat(template.getIntroText()).isEqualTo(TEMPLATE_FIELDS.introText());
    }

    /**
     * 커스텀 slug로 바꾸면 slug로 조회되고, 같은 slug는 다른 Creator가 가져갈 수 없다.
     * 누군가 다른 Creator의 자동 slug를 먼저 가져갔으면, 그 Creator의 Space는 번호를 붙인 slug로 만들어진다.
     */
    @Test
    void 커스텀_slug는_중복될_수_없고_선점된_자동_slug는_번호를_붙여_만든다() {
        Member admin = createMember("slug관리자", MemberRole.ADMIN);
        Member owner = createMember("slug선점자", MemberRole.USER);
        Member late = createMember("slug후발자", MemberRole.USER);
        Long templateId = creatorSpaceTemplateService.create(admin.getMemberId(), TEMPLATE_FIELDS).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);
        approve(admin, owner);
        Creator lateCreator = creatorRepository.saveAndFlush(new Creator(late.getMemberId(), "후발 크리에이터"));
        String lateAutoSlug = "creator-" + lateCreator.getCreatorId();

        profileService.changeSlug(owner.getMemberId(), lateAutoSlug);
        CreatorSpace lateSpace = creatorSpaceService.createFromActiveTemplateIfAbsent(lateCreator.getCreatorId());

        assertThat(lateSpace.getSlug()).isEqualTo(lateAutoSlug + "-2");
        assertThat(profileService.findBySlug(lateAutoSlug).creatorName())
                .isEqualTo(creatorRepository.findByMemberId(owner.getMemberId()).orElseThrow().getName());
        assertThatThrownBy(() -> profileService.changeSlug(late.getMemberId(), lateAutoSlug))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.SLUG_ALREADY_TAKEN);
        assertThatThrownBy(() -> profileService.changeSlug(owner.getMemberId(), "slug-again-" + lateCreator.getCreatorId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.SLUG_CHANGE_TOO_SOON);
    }

    private void approve(Member admin, Member applicant) {
        CreatorApplication application = creatorApplicationRepository.saveAndFlush(
                new CreatorApplication(applicant.getMemberId()));
        applicationIds.add(application.getId());
        creatorApplicationService.approve(admin.getMemberId(), application.getId());
    }

    private Member createMember(String name, MemberRole role) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, role));
        memberIds.add(member.getMemberId());
        return member;
    }
}
