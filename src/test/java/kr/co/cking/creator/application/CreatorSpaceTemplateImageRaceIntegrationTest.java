package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.application.dto.CreatorSpaceTemplateFields;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceImageKind;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.domain.CreatorSpaceTemplateImage;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.creator.repository.CreatorSpaceTemplateImageRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 템플릿 이미지 정리와 템플릿 저장이 같은 이미지에서 겹칠 때(이슈 #598)를 실제 DB 행 잠금으로 검증한다.
 * 저장소 객체 삭제는 DB 트랜잭션에 묶을 수 없으므로, "삭제 확정"과 "템플릿에서 쓰기 시작"이 같은 이미지 기록에 대한
 * 조건부 UPDATE로 직렬화되어 둘 중 하나만 성공하는지 본다. 순서는 래치로 고정하고, 대기 여부는 짧은 대기 뒤 완료 여부로 확인한다.
 *
 * <p>템플릿은 활성화하지 않고 저장만 하며, 이 테스트가 만든 템플릿·이미지 기록·회원만 지운다.
 */
@SpringBootTest
class CreatorSpaceTemplateImageRaceIntegrationTest {

    private static final String CDN = "https://cdn.example/";
    private static final long BLOCKED_CHECK_MILLIS = 400;
    private static final long TIMEOUT_SECONDS = 20;

    @Autowired private CreatorSpaceTemplateService templateService;
    @Autowired private CreatorSpaceProfileService profileService;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private CreatorSpaceRepository spaceRepository;
    @Autowired private CreatorSpaceTemplateImageRepository imageRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final List<String> keys = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> creatorMemberIds = new ArrayList<>();
    private Long adminId;

    /** 관리자 회원을 다른 스레드가 아니라 테스트 스레드에서 미리 만든다. */
    @BeforeEach
    void setUp() {
        admin();
    }

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        // 템플릿은 이 테스트의 관리자가 만든 것만 지운다(생성자 FK 때문에 회원보다 먼저).
        memberIds.forEach(memberId -> jdbcTemplate.update("DELETE FROM creator_space_template WHERE created_by = ?", memberId));
        keys.forEach(key -> jdbcTemplate.update("DELETE FROM creator_space_template_image WHERE object_key = ?", key));
        creatorMemberIds.forEach(memberId -> {
            jdbcTemplate.update("DELETE FROM creator_space WHERE creator_id IN (SELECT creator_id FROM creator WHERE member_id = ?)", memberId);
            jdbcTemplate.update("DELETE FROM creator WHERE member_id = ?", memberId);
        });
        memberIds.forEach(memberId -> jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId));
    }

    @Test
    void 삭제가_확정된_이미지를_템플릿에_저장하면_거절하고_템플릿을_만들지_않는다() {
        String key = image("DELETING");

        assertThatThrownBy(() -> templateService.create(admin(), fields(CDN + key)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE);

        assertThat(templatesReferencing(key)).isZero();
        assertThat(statusOf(key)).isEqualTo("DELETING");
    }

    @Test
    void 이미_지운_이미지의_옛_URL을_다시_붙여_넣어도_거절한다() {
        String key = image("DELETED");

        assertThatThrownBy(() -> templateService.create(admin(), fields(CDN + key)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE);

        assertThat(templatesReferencing(key)).isZero();
    }

    @Test
    void 정리_대상으로_선점됐지만_삭제가_확정되기_전이면_저장이_성공하고_이미지를_되돌린다() {
        String key = image("DELETE_PENDING");

        templateService.create(admin(), fields(CDN + key));

        assertThat(templatesReferencing(key)).isEqualTo(1);
        assertThat(statusOf(key)).isEqualTo("UPLOADED");
        assertThat(imageRepository.markDeleting(key, Instant.now())).isZero();
    }

    @Test
    void 기록이_없는_URL은_추적하지_않으므로_그대로_저장한다() {
        String untracked = "creator-space-profile-images/template/banner/" + UUID.randomUUID() + ".jpg";

        templateService.create(admin(), fields(CDN + untracked));

        assertThat(templatesReferencing(untracked)).isEqualTo(1);
    }

    /** 템플릿 저장이 먼저 이미지를 고정해 둔 동안 정리가 삭제를 확정하려 하면, 저장이 끝난 뒤 실패해 저장소를 지우지 않는다. */
    @Test
    void 저장이_이미지를_고정한_동안_삭제_확정은_기다렸다가_실패한다() throws Exception {
        String key = image("DELETE_PENDING");
        CountDownLatch pinned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> saving = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            templateService.create(admin(), fields(CDN + key));
            pinned.countDown();
            await(release);
        }));
        assertThat(pinned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Future<Integer> committing = executor.submit(() -> imageRepository.markDeleting(key, Instant.now()));
        Thread.sleep(BLOCKED_CHECK_MILLIS);
        assertThat(committing.isDone()).as("삭제 확정은 저장 트랜잭션의 행 잠금을 기다려야 한다").isFalse();

        release.countDown();
        saving.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(committing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isZero();
        assertThat(statusOf(key)).isEqualTo("UPLOADED");
        assertThat(templatesReferencing(key)).isEqualTo(1);
    }

    /** 정리가 삭제를 먼저 확정해 둔 동안 템플릿 저장이 시작되면, 확정이 끝난 뒤 저장이 거절되어 삭제된 이미지를 가리키지 않는다. */
    @Test
    void 삭제_확정이_진행되는_동안_시작한_템플릿_저장은_기다렸다가_거절된다() throws Exception {
        String key = image("DELETE_PENDING");
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Integer> committing = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            int updated = imageRepository.markDeleting(key, Instant.now());
            committed.countDown();
            await(release);
            return updated;
        }));
        assertThat(committed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Future<?> saving = executor.submit(() -> templateService.create(admin(), fields(CDN + key)));
        Thread.sleep(BLOCKED_CHECK_MILLIS);
        assertThat(saving.isDone()).as("템플릿 저장은 삭제 확정 트랜잭션의 행 잠금을 기다려야 한다").isFalse();

        release.countDown();
        assertThat(committing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
        assertThatThrownBy(() -> saving.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error.getCause()).getErrorCode())
                        .isEqualTo(CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE));
        assertThat(statusOf(key)).isEqualTo("DELETING");
        assertThat(templatesReferencing(key)).isZero();
    }

    // ---- Creator가 프로필 URL을 직접 바꾸는 경로(updateMine)도 같은 보호를 받는다 ----

    @Test
    void 삭제가_확정된_템플릿_이미지의_URL로_프로필을_수정하면_거절하고_Space를_바꾸지_않는다() {
        String key = image("DELETING");
        Member owner = creatorWithSpace(CDN + "default/banner.png");

        assertThatThrownBy(() -> profileService.updateMine(owner.getMemberId(), profileFields(CDN + key)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE);

        assertThat(bannerUrlOf(owner)).isEqualTo(CDN + "default/banner.png");
    }

    /** 현재 URL을 그대로 보내는 수정은 고정하지 않으므로, 이미 정리된 이미지를 가진 Space도 소개글을 고칠 수 있다. */
    @Test
    void 바뀌지_않는_URL은_고정하지_않아_정리된_이미지를_가진_Space도_수정할_수_있다() {
        String key = image("DELETED");
        Member owner = creatorWithSpace(CDN + key);

        profileService.updateMine(owner.getMemberId(), profileFields(CDN + key));

        assertThat(bannerUrlOf(owner)).isEqualTo(CDN + key);
        assertThat(statusOf(key)).isEqualTo("DELETED");
    }

    @Test
    void 프로필_수정이_이미지를_고정한_동안_삭제_확정은_기다렸다가_실패한다() throws Exception {
        String key = image("DELETE_PENDING");
        Member owner = creatorWithSpace(CDN + "default/banner.png");
        CountDownLatch pinned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> updating = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            profileService.updateMine(owner.getMemberId(), profileFields(CDN + key));
            pinned.countDown();
            await(release);
        }));
        assertThat(pinned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Future<Integer> committing = executor.submit(() -> imageRepository.markDeleting(key, Instant.now()));
        Thread.sleep(BLOCKED_CHECK_MILLIS);
        assertThat(committing.isDone()).as("삭제 확정은 프로필 수정 트랜잭션의 행 잠금을 기다려야 한다").isFalse();

        release.countDown();
        updating.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(committing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isZero();
        assertThat(statusOf(key)).isEqualTo("UPLOADED");
        assertThat(bannerUrlOf(owner)).isEqualTo(CDN + key);
    }

    /** 삭제를 먼저 확정해 둔 동안 시작한 프로필 수정은 확정이 끝난 뒤 거절되어 삭제된 이미지를 Space에 기록하지 않는다. */
    @Test
    void 삭제_확정이_진행되는_동안_시작한_프로필_수정은_기다렸다가_거절된다() throws Exception {
        String key = image("DELETE_PENDING");
        Member owner = creatorWithSpace(CDN + "default/banner.png");
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Integer> committing = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            int updated = imageRepository.markDeleting(key, Instant.now());
            committed.countDown();
            await(release);
            return updated;
        }));
        assertThat(committed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Future<?> updating = executor.submit(() -> profileService.updateMine(owner.getMemberId(), profileFields(CDN + key)));
        Thread.sleep(BLOCKED_CHECK_MILLIS);
        assertThat(updating.isDone()).as("프로필 수정은 삭제 확정 트랜잭션의 행 잠금을 기다려야 한다").isFalse();

        release.countDown();
        assertThat(committing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
        assertThatThrownBy(() -> updating.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error.getCause()).getErrorCode())
                        .isEqualTo(CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE));
        assertThat(statusOf(key)).isEqualTo("DELETING");
        assertThat(bannerUrlOf(owner)).isEqualTo(CDN + "default/banner.png");
    }

    private CreatorSpaceProfileFields profileFields(String bannerUrl) {
        return new CreatorSpaceProfileFields("새 소개", CDN + "default/profile.png", bannerUrl);
    }

    /** 회원·Creator·Space를 직접 만든다. Space의 프로필·배너는 전달한 URL(프로필은 기본값)로 시작한다. */
    private Member creatorWithSpace(String bannerUrl) {
        Member owner = memberRepository.saveAndFlush(new Member("경합크리에이터", null, null, MemberRole.USER));
        memberIds.add(owner.getMemberId());
        creatorMemberIds.add(owner.getMemberId());
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "경합크리에이터"));
        spaceRepository.saveAndFlush(CreatorSpace.fromTemplate(creator.getCreatorId(),
                new CreatorSpaceTemplate(1L, "소개", CDN + "default/profile.png", bannerUrl, "creator-{creatorId}"),
                "race-it-" + creator.getCreatorId()));
        return owner;
    }

    private String bannerUrlOf(Member owner) {
        return jdbcTemplate.queryForObject(
                "SELECT banner_image_url FROM creator_space WHERE creator_id IN (SELECT creator_id FROM creator WHERE member_id = ?)",
                String.class, owner.getMemberId());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private CreatorSpaceTemplateFields fields(String bannerUrl) {
        return new CreatorSpaceTemplateFields("소개", CDN + "default/profile.png", bannerUrl, "creator-{creatorId}");
    }

    /** 템플릿 이미지 기록을 만들고 {@code status}로 맞춘다. 올린 지 25시간이 지난 것으로 둔다. */
    private String image(String status) {
        String key = "creator-space-profile-images/template/banner/%s.jpg".formatted(UUID.randomUUID());
        keys.add(key);
        Instant created = Instant.now().minus(25, ChronoUnit.HOURS);
        imageRepository.save(CreatorSpaceTemplateImage.uploading(key, CreatorSpaceImageKind.BANNER, created));
        jdbcTemplate.update("UPDATE creator_space_template_image SET status = ? WHERE object_key = ?", status, key);
        return key;
    }

    private Long admin() {
        if (adminId == null) {
            Member member = memberRepository.saveAndFlush(new Member("경합관리자", null, null, MemberRole.ADMIN));
            memberIds.add(member.getMemberId());
            adminId = member.getMemberId();
        }
        return adminId;
    }

    private int templatesReferencing(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM creator_space_template WHERE created_by = ? AND INSTR(banner_image_url, ?) > 0",
                Integer.class, admin(), key);
    }

    private String statusOf(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM creator_space_template_image WHERE object_key = ?", String.class, key);
    }
}
