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

    @Column(name = "home_tab_enabled", nullable = false)
    private boolean homeTabEnabled;

    @Column(name = "missions_tab_enabled", nullable = false)
    private boolean missionsTabEnabled;

    @Column(name = "posts_tab_enabled", nullable = false)
    private boolean postsTabEnabled;

    @Column(name = "events_tab_enabled", nullable = false)
    private boolean eventsTabEnabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private CreatorSpace(
            Long creatorId, String introText, String profileImageUrl, String bannerImageUrl, String slug,
            boolean homeTabEnabled, boolean missionsTabEnabled, boolean postsTabEnabled, boolean eventsTabEnabled
    ) {
        this.creatorId = creatorId;
        this.introText = introText;
        this.profileImageUrl = profileImageUrl;
        this.bannerImageUrl = bannerImageUrl;
        this.slug = slug;
        this.homeTabEnabled = homeTabEnabled;
        this.missionsTabEnabled = missionsTabEnabled;
        this.postsTabEnabled = postsTabEnabled;
        this.eventsTabEnabled = eventsTabEnabled;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** Creator 본인이 홈·프로필을 수정한다. slug는 공유 URL의 식별자라 바꾸지 않는다. */
    public void updateProfile(
            String introText, String profileImageUrl, String bannerImageUrl,
            boolean homeTabEnabled, boolean missionsTabEnabled, boolean postsTabEnabled, boolean eventsTabEnabled
    ) {
        this.introText = introText;
        this.profileImageUrl = profileImageUrl;
        this.bannerImageUrl = bannerImageUrl;
        this.homeTabEnabled = homeTabEnabled;
        this.missionsTabEnabled = missionsTabEnabled;
        this.postsTabEnabled = postsTabEnabled;
        this.eventsTabEnabled = eventsTabEnabled;
    }

    public static CreatorSpace fromTemplate(Long creatorId, CreatorSpaceTemplate template, String slug) {
        return new CreatorSpace(
                creatorId,
                template.getIntroText(),
                template.getProfileImageUrl(),
                template.getBannerImageUrl(),
                slug,
                template.isHomeTabEnabled(),
                template.isMissionsTabEnabled(),
                template.isPostsTabEnabled(),
                template.isEventsTabEnabled()
        );
    }
}
