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
 * 관리자가 관리하는 기본 크리에이터 스페이스 템플릿이다.
 * Creator 승인 시점에 활성 템플릿의 값을 새 Creator 스페이스로 복사해 쓰며(후속 이슈),
 * 이후 템플릿이 바뀌어도 이미 생성된 스페이스는 바뀌지 않는다.
 */
@Entity
@Table(
        name = "creator_space_template",
        uniqueConstraints = @UniqueConstraint(name = "uk_creator_space_template_active", columnNames = "active_marker")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorSpaceTemplate {

    /** 활성 템플릿에서만 이 값을 갖는다. 그 외에는 NULL이어야 UNIQUE 제약이 유일성을 보장한다. */
    public static final Integer ACTIVE_MARKER = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "intro_text", nullable = false, length = 500)
    private String introText;

    @Column(name = "profile_image_url", nullable = false, length = 500)
    private String profileImageUrl;

    @Column(name = "banner_image_url", nullable = false, length = 500)
    private String bannerImageUrl;

    @Column(name = "slug_rule", nullable = false, length = 100)
    private String slugRule;

    @Column(name = "home_tab_enabled", nullable = false)
    private boolean homeTabEnabled;

    @Column(name = "missions_tab_enabled", nullable = false)
    private boolean missionsTabEnabled;

    @Column(name = "posts_tab_enabled", nullable = false)
    private boolean postsTabEnabled;

    @Column(name = "events_tab_enabled", nullable = false)
    private boolean eventsTabEnabled;

    @Column(name = "active_marker")
    private Integer activeMarker;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CreatorSpaceTemplate(
            Long adminId, String introText, String profileImageUrl, String bannerImageUrl, String slugRule,
            boolean homeTabEnabled, boolean missionsTabEnabled, boolean postsTabEnabled, boolean eventsTabEnabled
    ) {
        this.introText = introText;
        this.profileImageUrl = profileImageUrl;
        this.bannerImageUrl = bannerImageUrl;
        this.slugRule = slugRule;
        this.homeTabEnabled = homeTabEnabled;
        this.missionsTabEnabled = missionsTabEnabled;
        this.postsTabEnabled = postsTabEnabled;
        this.eventsTabEnabled = eventsTabEnabled;
        this.createdBy = adminId;
        this.updatedBy = adminId;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return activeMarker != null;
    }

    public void update(
            Long adminId, String introText, String profileImageUrl, String bannerImageUrl, String slugRule,
            boolean homeTabEnabled, boolean missionsTabEnabled, boolean postsTabEnabled, boolean eventsTabEnabled
    ) {
        this.introText = introText;
        this.profileImageUrl = profileImageUrl;
        this.bannerImageUrl = bannerImageUrl;
        this.slugRule = slugRule;
        this.homeTabEnabled = homeTabEnabled;
        this.missionsTabEnabled = missionsTabEnabled;
        this.postsTabEnabled = postsTabEnabled;
        this.eventsTabEnabled = eventsTabEnabled;
        touch(adminId);
    }

    public void activate(Long adminId) {
        this.activeMarker = ACTIVE_MARKER;
        touch(adminId);
    }

    public void deactivate(Long adminId) {
        this.activeMarker = null;
        touch(adminId);
    }

    private void touch(Long adminId) {
        this.updatedBy = adminId;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
