package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> applicationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        memberIds.forEach(memberId -> {
            jdbcTemplate.update(
                    "DELETE FROM creator_space_slug_reservation WHERE creator_id IN (SELECT creator_id FROM creator WHERE member_id = ?)",
                    memberId
            );
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

    @Test
    void 같은_Creator의_동시_slug_변경은_첫_변경_후_14일_제한을_적용한다() throws Exception {
        Member admin = createMember("동시변경관리자", MemberRole.ADMIN);
        Member owner = createMember("동시변경크리에이터", MemberRole.USER);
        Long templateId = creatorSpaceTemplateService.create(admin.getMemberId(), TEMPLATE_FIELDS).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);
        approve(admin, owner);

        CountDownLatch firstChanged = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                profileService.changeSlug(owner.getMemberId(), "first-slug");
                firstChanged.countDown();
                await(commitFirst);
            }));
            assertThat(firstChanged.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Object> second = executor.submit(() -> {
                secondStarted.countDown();
                try {
                    profileService.changeSlug(owner.getMemberId(), "second-slug");
                    return null;
                } catch (BusinessException exception) {
                    return exception.getErrorCode();
                }
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);
            commitFirst.countDown();

            first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(CreatorErrorCode.SLUG_CHANGE_TOO_SOON);
            assertThat(profileService.findMine(owner.getMemberId()).space().getSlug()).isEqualTo("first-slug");
        } finally {
            commitFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 수동_백필은_선점된_자동_slug_대신_빈_후보를_사용한다() throws Exception {
        Member admin = createMember("백필관리자", MemberRole.ADMIN);
        Member owner = createMember("백필선점자", MemberRole.USER);
        Member missing = createMember("백필대상", MemberRole.USER);
        Long templateId = creatorSpaceTemplateService.create(admin.getMemberId(), TEMPLATE_FIELDS).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);
        approve(admin, owner);
        Creator missingCreator = creatorRepository.saveAndFlush(new Creator(missing.getMemberId(), "백필 대상"));
        String baseSlug = "creator-" + missingCreator.getCreatorId();
        profileService.changeSlug(owner.getMemberId(), baseSlug);

        String backfillSql = Files.readString(Path.of("docs/domains/creator/manual-space-backfill.sql"), StandardCharsets.UTF_8);
        jdbcTemplate.execute(backfillSql);
        jdbcTemplate.execute(backfillSql);

        assertThat(profileService.findByCreatorId(missingCreator.getCreatorId()).space().getSlug())
                .isEqualTo(baseSlug + "-2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM creator_space WHERE creator_id = ?", Long.class, missingCreator.getCreatorId()))
                .isEqualTo(1L);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 변경 테스트 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 버린 이전 slug는 다른 Creator가 가져갈 수 없고, 버린 본인은 14일 변경 제한 안에서도 되돌릴 수 있다(이슈 #301).
     * 되돌리면서 버린 커스텀 slug도 예약되어 다른 Creator가 가져갈 수 없다.
     */
    @Test
    void 버린_slug는_다른_Creator가_못_쓰고_본인은_되돌릴_수_있다() {
        Member admin = createMember("예약관리자", MemberRole.ADMIN);
        Member owner = createMember("예약주인", MemberRole.USER);
        Member other = createMember("예약도전자", MemberRole.USER);
        Long templateId = creatorSpaceTemplateService.create(admin.getMemberId(), TEMPLATE_FIELDS).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);
        approve(admin, owner);
        approve(admin, other);
        Creator ownerCreator = creatorRepository.findByMemberId(owner.getMemberId()).orElseThrow();
        String autoSlug = "creator-" + ownerCreator.getCreatorId();
        String customSlug = "reserved-" + ownerCreator.getCreatorId();

        profileService.changeSlug(owner.getMemberId(), customSlug);

        assertSlugTaken(() -> profileService.changeSlug(other.getMemberId(), autoSlug));
        assertThat(profileService.changeSlug(owner.getMemberId(), autoSlug).space().getSlug()).isEqualTo(autoSlug);
        assertSlugTaken(() -> profileService.changeSlug(other.getMemberId(), customSlug));
        assertThatThrownBy(() -> profileService.findBySlug(customSlug))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 이전 slug는 14일 예약 중에는 다른 Creator가 못 쓰고, 만료되면 다시 사용할 수 있다. */
    @Test
    void 이전_slug는_예약_만료_뒤에_다른_Creator가_사용할_수_있다() {
        Member admin = createMember("만료예약관리자", MemberRole.ADMIN);
        Member owner = createMember("만료예약주인", MemberRole.USER);
        Member other = createMember("만료예약도전자", MemberRole.USER);
        Long templateId = creatorSpaceTemplateService.create(admin.getMemberId(), TEMPLATE_FIELDS).getTemplateId();
        creatorSpaceTemplateService.activate(admin.getMemberId(), templateId);
        approve(admin, owner);
        approve(admin, other);
        Creator ownerCreator = creatorRepository.findByMemberId(owner.getMemberId()).orElseThrow();
        String releasedSlug = "creator-" + ownerCreator.getCreatorId();

        profileService.changeSlug(owner.getMemberId(), "replacement-owner-slug");
        assertSlugTaken(() -> profileService.changeSlug(other.getMemberId(), releasedSlug));
        jdbcTemplate.update("UPDATE creator_space_slug_reservation SET expires_at = ? WHERE slug = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(15), releasedSlug);

        assertThat(profileService.changeSlug(other.getMemberId(), releasedSlug).space().getSlug())
                .isEqualTo(releasedSlug);
    }

    private void assertSlugTaken(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.SLUG_ALREADY_TAKEN);
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
