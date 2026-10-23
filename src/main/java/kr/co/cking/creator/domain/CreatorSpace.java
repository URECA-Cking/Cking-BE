package kr.co.cking.creator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 승인된 Creator의 Creator Space. 승인 시 기본 소개 문구와 이미지 없음으로 만든다(이슈 #754). 이미지가 없으면 FE가 기본 이미지를 보여 준다.
 * 템플릿 기능을 쓰던 시기에 만든 Space는 그때 복사한 이미지 URL을 그대로 갖는다.
 */
@Entity
@Table(
        name = "creator_space",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_creator_space_creator", columnNames = "creator_id"),
                @UniqueConstraint(name = "uk_creator_space_slug", columnNames = "slug")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorSpace {

    /** 승인 시 Space에 넣는 소개 문구. Creator가 스페이스 편집에서 바꾼다. */
    public static final String DEFAULT_INTRO_TEXT = "크리에이터와 함께하는 공간이에요";

    /** 승인 시 자동으로 만드는 slug. 끝 숫자열이 creatorId라 서로 다른 Creator의 자동 slug는 겹치지 않는다. */
    public static final String DEFAULT_SLUG_RULE = "creator-" + CreatorSpaceSlugRule.PLACEHOLDER;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "space_id")
    private Long spaceId;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "intro_text", nullable = false, length = 500)
    private String introText;

    @Column(name = "content_revision", nullable = false)
    private long contentRevision = 1;

    /** null이면 프로필 이미지가 없다. */
    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    /** 업로드된 Creator Space 프로필 이미지의 내부 Object Storage 키. 기존 URL 기반 Space는 null이다. */
    @Column(name = "profile_image_key", length = 200)
    private String profileImageKey;

    /** 업로드 이미지 연결을 해제할 때 복귀할, Space 생성·기존 URL 수정 시점의 기본 프로필 이미지다. null이면 이미지 없음으로 돌아간다. */
    @Column(name = "default_profile_image_url", length = 500)
    private String defaultProfileImageUrl;

    /** null이면 배너가 없다. */
    @Column(name = "banner_image_url", length = 500)
    private String bannerImageUrl;

    /** 업로드된 Creator Space 배너 이미지의 내부 Object Storage 키(이슈 #590). 기존 URL 기반 Space는 null이다. */
    @Column(name = "banner_image_key", length = 200)
    private String bannerImageKey;

    /** 업로드 배너 연결을 해제할 때 복귀할, Space 생성·기존 URL 수정 시점의 기본 배너 이미지다. null이면 배너 없음으로 돌아간다. */
    @Column(name = "default_banner_image_url", length = 500)
    private String defaultBannerImageUrl;

    @Column(name = "slug", nullable = false, length = 100)
    private String slug;

    /** 마지막 커스텀 slug 변경 시각(UTC). null이면 자동 slug를 아직 바꾸지 않았다. */
    @Column(name = "slug_changed_at")
    private LocalDateTime slugChangedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private CreatorSpace(
            Long creatorId, String introText, String profileImageUrl, String bannerImageUrl, String slug
    ) {
        this.creatorId = creatorId;
        this.introText = introText;
        this.profileImageUrl = profileImageUrl;
        this.defaultProfileImageUrl = profileImageUrl;
        this.bannerImageUrl = bannerImageUrl;
        this.defaultBannerImageUrl = bannerImageUrl;
        this.slug = slug;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Creator 본인이 홈·프로필을 수정한다. slug는 이 메서드로 바꾸지 않는다. 업로드한 프로필 사진·배너가 연결돼 있으면 그 이미지의
     * URL 입력은 무시하고 현재 연결을 유지한다(교체·복귀는 {@link #updateProfileImage}·{@link #updateBannerImage} 등으로 한다).
     * 이미지 URL이 null이면 요청에 없었다는 뜻이라 그대로 두고, 빈 문자열이면 이미지 없음으로 바꾼다(이슈 #754).
     */
    public void updateProfile(String introText, String profileImageUrl, String bannerImageUrl) {
        if (!ContentIntro.hash(this.introText).equals(ContentIntro.hash(introText))) contentRevision++;
        this.introText = introText;
        if (profileImageKey == null && profileImageUrl != null) {
            this.profileImageUrl = blankToNull(profileImageUrl);
            this.defaultProfileImageUrl = this.profileImageUrl;
        }
        if (bannerImageKey == null && bannerImageUrl != null) {
            this.bannerImageUrl = blankToNull(bannerImageUrl);
            this.defaultBannerImageUrl = this.bannerImageUrl;
        }
    }

    /** 빈 문자열은 이미지 없음이라 null로 저장한다. */
    private static String blankToNull(String url) {
        return url.isBlank() ? null : url;
    }

    public void updateProfileImage(String profileImageKey, String profileImageUrl) {
        this.profileImageKey = profileImageKey;
        this.profileImageUrl = profileImageUrl;
    }

    public void restoreDefaultProfileImage() {
        this.profileImageKey = null;
        this.profileImageUrl = defaultProfileImageUrl;
    }

    /** 업로드한 배너 이미지를 현재 배너로 연결한다(이슈 #590). */
    public void updateBannerImage(String bannerImageKey, String bannerImageUrl) {
        this.bannerImageKey = bannerImageKey;
        this.bannerImageUrl = bannerImageUrl;
    }

    /** 업로드한 배너 연결을 해제하고 기본 배너로 돌아간다. */
    public void restoreDefaultBannerImage() {
        this.bannerImageKey = null;
        this.bannerImageUrl = defaultBannerImageUrl;
    }

    /** 커스텀 slug로 바꾼다. 형식·예약어·중복·변경 간격 검증은 호출하는 Application이 먼저 한다. */
    public void changeSlug(String slug, LocalDateTime changedAt) {
        this.slug = slug;
        this.slugChangedAt = changedAt;
    }

    /**
     * 예약 기간 안에 본인이 버린 slug로 되돌린다(이슈 #301). 변경 시각은 갱신하지 않아, 되돌리기를
     * 이용해 새 slug로 바꾸는 14일 제한을 우회할 수 없게 한다.
     */
    public void revertSlug(String slug) {
        this.slug = slug;
    }

    /** 다음에 slug를 바꿀 수 있는 시각(UTC). 한 번도 바꾸지 않았으면 null이며 바로 바꿀 수 있다. */
    public LocalDateTime slugChangeableAt() {
        return slugChangedAt == null ? null : slugChangedAt.plus(CreatorSpaceCustomSlug.CHANGE_INTERVAL);
    }

    public boolean canChangeSlugAt(LocalDateTime now) {
        LocalDateTime changeableAt = slugChangeableAt();
        return changeableAt == null || !now.isBefore(changeableAt);
    }

    /** 승인 시 만드는 Space. 기본 소개 문구에 이미지는 없다. */
    public static CreatorSpace createDefault(Long creatorId, String slug) {
        return new CreatorSpace(creatorId, DEFAULT_INTRO_TEXT, null, null, slug);
    }
}
