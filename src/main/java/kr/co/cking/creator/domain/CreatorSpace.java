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
 * 승인된 크리에이터 전용 Creator Space다. 승인 시점에 활성 {@link CreatorSpaceTemplate}의
 * 값을 복사해 만들며, 이후 템플릿이 바뀌어도 이미 만들어진 Space는 영향받지 않는다.
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

    /**
     * 템플릿의 현재 값을 값 그대로 복사해 새 인스턴스를 만든다. 템플릿 엔티티를 참조로
     * 들고 있지 않으므로 이후 템플릿이 수정·비활성화돼도 이 인스턴스는 영향받지 않는다.
     */
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
