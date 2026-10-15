package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceImageKind;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.domain.CreatorSpaceTemplateImage;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.creator.repository.CreatorSpaceTemplateImageRepository;
import kr.co.cking.creator.repository.CreatorSpaceTemplateRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 템플릿 이미지 정리의 참조 대조를 실제 DB로 검증한다(이슈 #598, V76). 템플릿 이미지는 key가 아니라 CDN URL로
 * 템플릿·Space에 저장되므로, 정리 쿼리가 템플릿 2곳과 Space 4곳의 URL 컬럼에서 key를 찾아 쓰는 이미지를 지우지 않는지 본다.
 *
 * <p>템플릿은 활성화하지 않고 저장만 하고(활성 템플릿 유일성과 무관), 이 테스트가 만든 템플릿·Space·Creator·회원·이미지 행만 지운다.
 * 정리 쿼리는 24시간이 지난 모든 미참조 기록을 선점하므로, 같은 DB에 다른 템플릿 이미지 기록이 있으면 함께 바뀔 수 있다.
 */
@SpringBootTest
class CreatorSpaceTemplateImageCleanupIntegrationTest {

    private static final String CDN = "https://cdn.example/";

    @Autowired private CreatorSpaceTemplateImageRepository imageRepository;
    @Autowired private CreatorSpaceTemplateRepository templateRepository;
    @Autowired private CreatorSpaceRepository spaceRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<String> keys = new ArrayList<>();
    private final List<Long> templateIds = new ArrayList<>();
    private final List<Long> creatorMemberIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private Long adminId;

    @AfterEach
    void cleanUp() {
        keys.forEach(key -> jdbcTemplate.update("DELETE FROM creator_space_template_image WHERE object_key = ?", key));
        templateIds.forEach(id -> jdbcTemplate.update("DELETE FROM creator_space_template WHERE template_id = ?", id));
        creatorMemberIds.forEach(memberId -> {
            jdbcTemplate.update("DELETE FROM creator_space WHERE creator_id IN (SELECT creator_id FROM creator WHERE member_id = ?)", memberId);
            jdbcTemplate.update("DELETE FROM creator WHERE member_id = ?", memberId);
        });
        memberIds.forEach(memberId -> jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId));
    }

    @Test
    void 올린_지_24시간_지난_미참조_업로드는_삭제_대기로_선점한다() {
        String key = uploaded("banner", hoursAgo(25));

        claim();

        assertThat(statusOf(key)).isEqualTo("DELETE_PENDING");
    }

    @Test
    void 올리다_끝나지_않은_업로드도_24시간이_지나면_선점한다() {
        String key = newKey("banner");
        imageRepository.save(CreatorSpaceTemplateImage.uploading(key, CreatorSpaceImageKind.BANNER, hoursAgo(25)));

        claim();

        assertThat(statusOf(key)).isEqualTo("DELETE_PENDING");
    }

    @Test
    void 올린_지_24시간이_안_된_업로드는_참조가_없어도_선점하지_않는다() {
        String key = uploaded("banner", hoursAgo(23));

        claim();

        assertThat(statusOf(key)).isEqualTo("UPLOADED");
    }

    @Test
    void 템플릿의_배너_URL이_참조하면_선점하지_않는다() {
        String key = uploaded("banner", hoursAgo(25));
        template(CDN + "default/profile.png", CDN + key);

        claim();

        assertThat(statusOf(key)).isEqualTo("UPLOADED");
    }

    @Test
    void 템플릿의_프로필_URL이_참조하면_선점하지_않는다() {
        String key = uploaded("profile", hoursAgo(25));
        template(CDN + key, CDN + "default/banner.png");

        claim();

        assertThat(statusOf(key)).isEqualTo("UPLOADED");
    }

    @Test
    void Space의_기본_배너만_참조해도_선점하지_않는다() {
        String key = uploaded("banner", hoursAgo(25));
        CreatorSpace space = spaceFromTemplate(CDN + "default/profile.png", CDN + key);
        // 현재 배너는 다른 이미지로 바뀌었고 기본 배너(복귀 대상)만 이 이미지를 가리킨다.
        space.updateBannerImage("creator-space-profile-images/1/banner/x.jpg", CDN + "creator-space-profile-images/1/banner/x.jpg");
        spaceRepository.saveAndFlush(space);

        claim();

        assertThat(statusOf(key)).isEqualTo("UPLOADED");
    }

    @Test
    void Space의_현재_배너만_참조해도_선점하지_않는다() {
        String key = uploaded("banner", hoursAgo(25));
        CreatorSpace space = spaceFromTemplate(CDN + "default/profile.png", CDN + "default/banner.png");
        space.updateBannerImage(key, CDN + key);
        spaceRepository.saveAndFlush(space);

        claim();

        assertThat(statusOf(key)).isEqualTo("UPLOADED");
    }

    @Test
    void Space의_기본_프로필만_참조해도_선점하지_않는다() {
        String key = uploaded("profile", hoursAgo(25));
        CreatorSpace space = spaceFromTemplate(CDN + key, CDN + "default/banner.png");
        space.updateProfileImage("creator-space-profile-images/1/x.jpg", CDN + "creator-space-profile-images/1/x.jpg");
        spaceRepository.saveAndFlush(space);

        claim();

        assertThat(statusOf(key)).isEqualTo("UPLOADED");
    }

    @Test
    void Space의_현재_프로필만_참조해도_선점하지_않는다() {
        String key = uploaded("profile", hoursAgo(25));
        CreatorSpace space = spaceFromTemplate(CDN + "default/profile.png", CDN + "default/banner.png");
        space.updateProfileImage(key, CDN + key);
        spaceRepository.saveAndFlush(space);

        claim();

        assertThat(statusOf(key)).isEqualTo("UPLOADED");
    }

    @Test
    void CDN_주소가_바뀌어도_key가_URL에_들어_있으면_참조로_본다() {
        String key = uploaded("banner", hoursAgo(25));
        template(CDN + "default/profile.png", "https://moved-cdn.example/assets/" + key + "?v=2");

        claim();

        assertThat(statusOf(key)).isEqualTo("UPLOADED");
    }

    @Test
    void 쓰이던_이미지가_더는_어디에서도_참조되지_않으면_다음_정리에서_선점한다() {
        String key = uploaded("banner", hoursAgo(25));
        CreatorSpaceTemplate template = template(CDN + "default/profile.png", CDN + key);
        claim();
        assertThat(statusOf(key)).isEqualTo("UPLOADED");

        template.update(adminId, "소개", CDN + "default/profile.png", CDN + "default/banner.png", "creator-{creatorId}");
        templateRepository.saveAndFlush(template);
        claim();

        assertThat(statusOf(key)).isEqualTo("DELETE_PENDING");
    }

    @Test
    void 선점한_뒤_템플릿에_저장되어_쓰이게_되면_삭제_직전_확인에서_업로드_상태로_되돌린다() {
        String key = uploaded("banner", hoursAgo(25));
        claim();
        assertThat(statusOf(key)).isEqualTo("DELETE_PENDING");
        template(CDN + "default/profile.png", CDN + key);

        int restored = imageRepository.restoreIfReferenced(key, Instant.now());

        assertThat(restored).isEqualTo(1);
        assertThat(statusOf(key)).isEqualTo("UPLOADED");
    }

    @Test
    void 선점한_뒤에도_참조가_없으면_되돌리지_않고_삭제_대기를_유지한다() {
        String key = uploaded("banner", hoursAgo(25));
        claim();

        int restored = imageRepository.restoreIfReferenced(key, Instant.now());

        assertThat(restored).isZero();
        assertThat(statusOf(key)).isEqualTo("DELETE_PENDING");
    }

    @Test
    void 완료_표시는_업로드_중인_기록만_업로드_완료로_바꾼다() {
        String key = newKey("banner");
        imageRepository.save(CreatorSpaceTemplateImage.uploading(key, CreatorSpaceImageKind.BANNER, Instant.now()));

        assertThat(imageRepository.markUploaded(key, Instant.now())).isEqualTo(1);
        assertThat(imageRepository.markUploaded(key, Instant.now())).isZero();
        assertThat(statusOf(key)).isEqualTo("UPLOADED");
    }

    private void claim() {
        Instant now = Instant.now();
        imageRepository.claimStaleUnreferenced(now.minus(24, ChronoUnit.HOURS), now);
    }

    private Instant hoursAgo(long hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS);
    }

    private String newKey(String kind) {
        String key = "creator-space-profile-images/template/%s/it-%s.jpg".formatted(kind, UUID.randomUUID());
        keys.add(key);
        return key;
    }

    /** 업로드 완료 상태의 기록을 {@code createdAt} 시각으로 만든다. */
    private String uploaded(String kind, Instant createdAt) {
        String key = newKey(kind);
        CreatorSpaceImageKind imageKind = kind.equals("banner") ? CreatorSpaceImageKind.BANNER : CreatorSpaceImageKind.PROFILE;
        imageRepository.save(CreatorSpaceTemplateImage.uploading(key, imageKind, createdAt));
        imageRepository.markUploaded(key, createdAt);
        return key;
    }

    private CreatorSpaceTemplate template(String profileUrl, String bannerUrl) {
        CreatorSpaceTemplate template = templateRepository.saveAndFlush(
                new CreatorSpaceTemplate(admin(), "소개", profileUrl, bannerUrl, "creator-{creatorId}"));
        templateIds.add(template.getTemplateId());
        return template;
    }

    /** Space는 템플릿 값을 복사해 만든다. 템플릿은 저장하지 않고 URL만 전달하는 원본이다. */
    private CreatorSpace spaceFromTemplate(String profileUrl, String bannerUrl) {
        Member owner = member("정리테스트", MemberRole.USER);
        creatorMemberIds.add(owner.getMemberId());
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "정리테스트"));
        return CreatorSpace.fromTemplate(creator.getCreatorId(),
                new CreatorSpaceTemplate(1L, "소개", profileUrl, bannerUrl, "creator-{creatorId}"),
                "tmpl-img-it-" + creator.getCreatorId());
    }

    private Long admin() {
        if (adminId == null) {
            adminId = member("정리관리자", MemberRole.ADMIN).getMemberId();
        }
        return adminId;
    }

    private Member member(String name, MemberRole role) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, role));
        memberIds.add(member.getMemberId());
        return member;
    }

    private String statusOf(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM creator_space_template_image WHERE object_key = ?", String.class, key);
    }
}
