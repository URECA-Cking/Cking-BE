package kr.co.cking.creator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.PrePersist;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import kr.co.cking.common.exception.BusinessException;

@Entity
@Table(
        name = "creator",
        uniqueConstraints = @UniqueConstraint(name = "uk_creator_member", columnNames = "member_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Creator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "activity_name", nullable = false, length = 20)
    private String activityName;

    @Column(name = "activity_name_normalized", nullable = false, length = 20)
    private String activityNameNormalized;

    @Column(name = "activity_name_changed_at")
    private LocalDateTime activityNameChangedAt;

    @Column(name = "previous_activity_name", length = 20)
    private String previousActivityName;

    @Column(name = "previous_activity_name_normalized", length = 20)
    private String previousActivityNameNormalized;

    @Column(name = "activity_name_configured", nullable = false)
    private boolean activityNameConfigured;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Creator(Long memberId, String name) {
        // 레거시 생성자(테스트·개발 fixture 호환): 이름이 활동명 규칙에 맞으면 그대로 공개 이름으로 쓴다.
        // 실제 승인 경로는 아래 활동명을 명시하는 생성자만 사용하며, 기존 운영 Creator의 미설정 상태는 V58 이관으로 만든다.
        this(memberId, name, defaultActivityName(memberId, name), true);
    }

    public Creator(Long memberId, String name, CreatorActivityName activityName) {
        this(memberId, name, activityName, true);
    }

    private Creator(Long memberId, String name, CreatorActivityName activityName, boolean activityNameConfigured) {
        this.memberId = memberId;
        this.name = name;
        this.activityName = activityName.value();
        this.activityNameNormalized = activityName.normalized();
        this.activityNameConfigured = activityNameConfigured;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    private static CreatorActivityName defaultActivityName(Long memberId, String name) {
        try {
            return CreatorActivityName.of(name);
        } catch (IllegalArgumentException exception) {
            return CreatorActivityName.temporaryFor(memberId);
        }
    }

    /** 공개 응답에는 OAuth 원본 name이 아닌 활동명만 사용한다. */
    public String publicDisplayName() {
        return activityName;
    }

    public void requireActivityNameConfigured() {
        if (!activityNameConfigured) {
            throw new BusinessException(CreatorErrorCode.ACTIVITY_NAME_SETUP_REQUIRED);
        }
    }

    public boolean hasCurrentActivityName(CreatorActivityName requested) {
        return activityNameNormalized.equals(requested.normalized());
    }

    public void setInitialActivityName(CreatorActivityName activityName) {
        this.activityName = activityName.value();
        this.activityNameNormalized = activityName.normalized();
        this.activityNameConfigured = true;
    }

    public void changeActivityName(CreatorActivityName activityName, LocalDateTime changedAt) {
        this.previousActivityName = this.activityName;
        this.previousActivityNameNormalized = this.activityNameNormalized;
        this.activityName = activityName.value();
        this.activityNameNormalized = activityName.normalized();
        this.activityNameChangedAt = changedAt;
        this.activityNameConfigured = true;
    }

    public void revertActivityName() {
        if (previousActivityName == null || previousActivityNameNormalized == null) {
            throw new IllegalStateException("복귀할 이전 활동명이 없습니다.");
        }
        String currentName = activityName;
        String currentNormalized = activityNameNormalized;
        this.activityName = previousActivityName;
        this.activityNameNormalized = previousActivityNameNormalized;
        this.previousActivityName = currentName;
        this.previousActivityNameNormalized = currentNormalized;
        this.activityNameConfigured = true;
    }

    public LocalDateTime activityNameChangeableAt() {
        return activityNameChangedAt == null ? null : activityNameChangedAt.plus(CreatorActivityName.CHANGE_INTERVAL);
    }

    public boolean canChangeActivityNameAt(LocalDateTime now) {
        LocalDateTime changeableAt = activityNameChangeableAt();
        return changeableAt == null || !now.isBefore(changeableAt);
    }

    @PrePersist
    private void assignCreatedAtIfMissing() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
