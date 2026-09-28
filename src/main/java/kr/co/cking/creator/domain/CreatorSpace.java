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

/** 승인 시점의 템플릿 값을 독립적으로 보관하는 Creator Space. */
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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "space_id")
    private Long spaceId;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "intro_text", nullable = false, length = 500)
    private String introText;

    @Column(name = "profile_image_url", nullable = false, length = 500)
    private String profileImageUrl;

    @Column(name = "banner_image_url", nullable = false, length = 500)
    private String bannerImageUrl;

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
        this.bannerImageUrl = bannerImageUrl;
        this.slug = slug;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** Creator 본인이 홈·프로필을 수정한다. slug는 이 메서드로 바꾸지 않는다. */
    public void updateProfile(String introText, String profileImageUrl, String bannerImageUrl) {
        this.introText = introText;
        this.profileImageUrl = profileImageUrl;
        this.bannerImageUrl = bannerImageUrl;
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

    public static CreatorSpace fromTemplate(Long creatorId, CreatorSpaceTemplate template, String slug) {
        return new CreatorSpace(
                creatorId,
                template.getIntroText(),
                template.getProfileImageUrl(),
                template.getBannerImageUrl(),
                slug
        );
    }
}
