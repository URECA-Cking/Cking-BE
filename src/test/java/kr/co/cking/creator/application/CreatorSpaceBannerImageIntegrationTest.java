package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceFixtures;
import kr.co.cking.creator.domain.CreatorSpaceProfileImage;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creator Space 배너 이미지 연결·교체·복귀를 실제 DB로 검증한다(이슈 #590, V75).
 * 객체 저장소는 쓰지 않고, 업로드 완료 상태의 기록만 직접 만들어 연결 규칙(종류·소유·상태)과 상태 전이를 확인한다.
 *
 * <p>Creator와 Space는 승인 흐름이나 템플릿 활성화를 거치지 않고 직접 만든다. 템플릿을 활성화하면 이미 활성인 다른 템플릿의
 * {@code updated_by}가 이 테스트의 관리자로 바뀌고 활성 상태도 달라져, 정리 때 관리자 삭제가 FK 오류로 실패하거나 공유 DB의
 * 활성 템플릿이 바뀐 채 남는다. 여기서는 이 테스트가 만든 member·creator·space·이미지 행만 만들고 지운다.
 */
@SpringBootTest
class CreatorSpaceBannerImageIntegrationTest {

    @Autowired private CreatorSpaceProfileImageLinkService linkService;
    @Autowired private CreatorSpaceProfileService profileService;
    @Autowired private CreatorSpaceProfileImageRepository imageRepository;
    @Autowired private CreatorSpaceRepository spaceRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> memberIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        memberIds.forEach(memberId -> {
            jdbcTemplate.update(
                    "DELETE FROM creator_space_profile_image WHERE creator_id IN (SELECT creator_id FROM creator WHERE member_id = ?)", memberId);
            jdbcTemplate.update(
                    "DELETE FROM creator_space WHERE creator_id IN (SELECT creator_id FROM creator WHERE member_id = ?)", memberId);
            jdbcTemplate.update("DELETE FROM creator WHERE member_id = ?", memberId);
        });
        memberIds.forEach(memberId -> jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId));
    }

    @Test
    void Space는_생성_시점의_배너를_기본_배너로_갖고_업로드_배너는_없다() {
        Member owner = creatorWithSpace("배너기본값");
        CreatorSpace space = spaceOf(owner);

        assertThat(space.getBannerImageKey()).isNull();
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/banner.png");
        assertThat(space.getDefaultBannerImageUrl()).isEqualTo("https://img/banner.png");
    }

    @Test
    void 배너로_올린_이미지를_연결하면_CDN_URL이_현재_배너가_되고_이미지는_연결_상태가_된다() {
        Member owner = creatorWithSpace("배너연결");
        Long creatorId = creatorIdOf(owner);
        String key = uploadedBanner(creatorId, "a");

        linkService.linkBanner(owner.getMemberId(), key);

        CreatorSpace space = spaceOf(owner);
        assertThat(space.getBannerImageKey()).isEqualTo(key);
        assertThat(space.getBannerImageUrl()).endsWith(key);
        assertThat(space.getDefaultBannerImageUrl()).isEqualTo("https://img/banner.png");
        assertThat(statusOf(key)).isEqualTo("LINKED");
    }

    @Test
    void 같은_키를_다시_연결해도_성공하고_상태는_그대로다() {
        Member owner = creatorWithSpace("배너멱등");
        String key = uploadedBanner(creatorIdOf(owner), "a");
        linkService.linkBanner(owner.getMemberId(), key);

        linkService.linkBanner(owner.getMemberId(), key);

        assertThat(spaceOf(owner).getBannerImageKey()).isEqualTo(key);
        assertThat(statusOf(key)).isEqualTo("LINKED");
    }

    @Test
    void 프로필_사진으로_올린_키는_배너로_연결할_수_없고_배너로_올린_키는_프로필로_연결할_수_없다() {
        Member owner = creatorWithSpace("종류구분");
        Long creatorId = creatorIdOf(owner);
        String profileKey = uploadedProfile(creatorId, "p");
        String bannerKey = uploadedBanner(creatorId, "b");

        assertThatThrownBy(() -> linkService.linkBanner(owner.getMemberId(), profileKey))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_BANNER_IMAGE);
        assertThatThrownBy(() -> linkService.link(owner.getMemberId(), bannerKey))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_PROFILE_IMAGE);

        assertThat(statusOf(profileKey)).isEqualTo("UPLOADED");
        assertThat(statusOf(bannerKey)).isEqualTo("UPLOADED");
        CreatorSpace space = spaceOf(owner);
        assertThat(space.getBannerImageKey()).isNull();
        assertThat(space.getProfileImageKey()).isNull();
    }

    @Test
    void 다른_Creator의_배너_키는_연결할_수_없다() {
        Member owner = creatorWithSpace("배너소유자");
        Member other = creatorWithSpace("배너다른사람");
        String otherKey = uploadedBanner(creatorIdOf(other), "x");

        assertThatThrownBy(() -> linkService.linkBanner(owner.getMemberId(), otherKey))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_BANNER_IMAGE);

        assertThat(statusOf(otherKey)).isEqualTo("UPLOADED");
        assertThat(spaceOf(owner).getBannerImageKey()).isNull();
    }

    @Test
    void 업로드가_끝나지_않은_배너_키는_연결할_수_없다() {
        Member owner = creatorWithSpace("배너미완료");
        Long creatorId = creatorIdOf(owner);
        String key = "creator-space-profile-images/%d/banner/uploading.jpg".formatted(creatorId);
        imageRepository.save(CreatorSpaceProfileImage.uploadingBanner(key, creatorId, Instant.now()));

        assertThatThrownBy(() -> linkService.linkBanner(owner.getMemberId(), key))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_BANNER_IMAGE);

        assertThat(statusOf(key)).isEqualTo("UPLOADING");
    }

    @Test
    void 새_배너로_교체하면_이전_배너는_삭제_대기로_바뀐다() {
        Member owner = creatorWithSpace("배너교체");
        Long creatorId = creatorIdOf(owner);
        String first = uploadedBanner(creatorId, "first");
        String second = uploadedBanner(creatorId, "second");
        linkService.linkBanner(owner.getMemberId(), first);

        linkService.linkBanner(owner.getMemberId(), second);

        assertThat(spaceOf(owner).getBannerImageKey()).isEqualTo(second);
        assertThat(statusOf(first)).isEqualTo("DELETE_PENDING");
        assertThat(statusOf(second)).isEqualTo("LINKED");
    }

    @Test
    void 수정_요청에서_배너_키를_null로_보내면_기본_배너로_돌아가고_이전_배너는_삭제_대기로_바뀐다() {
        Member owner = creatorWithSpace("배너복귀");
        String key = uploadedBanner(creatorIdOf(owner), "a");
        linkService.linkBanner(owner.getMemberId(), key);
        String currentUrl = spaceOf(owner).getBannerImageUrl();

        // 클라이언트는 현재 배너 URL(업로드 이미지)을 그대로 보내므로, 그 값이 기본 배너로 저장되면 안 된다.
        profileService.updateMine(owner.getMemberId(), new CreatorSpaceProfileFields(
                "새 소개", "https://img/p2.png", currentUrl, null, false, null, true));

        CreatorSpace space = spaceOf(owner);
        assertThat(space.getBannerImageKey()).isNull();
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/banner.png");
        assertThat(space.getDefaultBannerImageUrl()).isEqualTo("https://img/banner.png");
        assertThat(statusOf(key)).isEqualTo("DELETE_PENDING");
    }

    @Test
    void 배너_키를_생략한_수정은_연결된_배너를_유지한다() {
        Member owner = creatorWithSpace("배너유지");
        String key = uploadedBanner(creatorIdOf(owner), "a");
        linkService.linkBanner(owner.getMemberId(), key);
        String currentUrl = spaceOf(owner).getBannerImageUrl();

        profileService.updateMine(owner.getMemberId(), new CreatorSpaceProfileFields(
                "새 소개", "https://img/p2.png", currentUrl));

        CreatorSpace space = spaceOf(owner);
        assertThat(space.getIntroText()).isEqualTo("새 소개");
        assertThat(space.getBannerImageKey()).isEqualTo(key);
        assertThat(space.getBannerImageUrl()).isEqualTo(currentUrl);
        assertThat(statusOf(key)).isEqualTo("LINKED");
    }

    private String uploadedBanner(Long creatorId, String name) {
        String key = "creator-space-profile-images/%d/banner/%s.jpg".formatted(creatorId, name);
        imageRepository.save(CreatorSpaceProfileImage.uploadingBanner(key, creatorId, Instant.now()));
        imageRepository.markUploaded(key, Instant.now());
        return key;
    }

    private String uploadedProfile(Long creatorId, String name) {
        String key = "creator-space-profile-images/%d/%s.jpg".formatted(creatorId, name);
        imageRepository.save(CreatorSpaceProfileImage.uploading(key, creatorId, Instant.now()));
        imageRepository.markUploaded(key, Instant.now());
        return key;
    }

    private String statusOf(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM creator_space_profile_image WHERE object_key = ?", String.class, key);
    }

    private CreatorSpace spaceOf(Member owner) {
        return spaceRepository.findByCreatorId(creatorIdOf(owner)).orElseThrow();
    }

    private Long creatorIdOf(Member owner) {
        Creator creator = creatorRepository.findByMemberId(owner.getMemberId()).orElseThrow();
        return creator.getCreatorId();
    }

    /** 회원·Creator·Space를 직접 만든다. Space의 기본 배너는 {@code https://img/banner.png}다. */
    private Member creatorWithSpace(String name) {
        Member owner = createMember(name);
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), name));
        spaceRepository.saveAndFlush(CreatorSpaceFixtures.space(creator.getCreatorId(), "소개", "https://img/profile.png", "https://img/banner.png", "banner-it-" + creator.getCreatorId()));
        return owner;
    }

    private Member createMember(String name) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, MemberRole.USER));
        memberIds.add(member.getMemberId());
        return member;
    }
}
