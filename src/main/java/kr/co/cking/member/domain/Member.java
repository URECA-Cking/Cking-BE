package kr.co.cking.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    public static final String NICKNAME_NOT_CONFIGURED = "닉네임 미설정";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MemberRole role;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted;

    /** OAuth 제공자 이름과 분리된 서비스 공개 활동명. 최초 가입·기존 회원은 설정 전까지 null이다. */
    @Column(name = "nickname", length = MemberNickname.MAX_LENGTH)
    private String nickname;

    /** NFC 정규화 후 Locale.ROOT 소문자로 만든 전역 중복 판정 키. */
    @Column(name = "nickname_normalized", length = MemberNickname.MAX_LENGTH)
    private String nicknameNormalized;

    /** 최초 설정 뒤 실제 닉네임을 바꾼 마지막 시각(UTC). 되돌리기는 갱신하지 않는다. */
    @Column(name = "nickname_changed_at")
    private LocalDateTime nicknameChangedAt;

    /** 바로 직전에 쓰던 닉네임. 예약 기간 안의 복귀 대상만 보관한다. */
    @Column(name = "previous_nickname", length = MemberNickname.MAX_LENGTH)
    private String previousNickname;

    @Column(name = "previous_nickname_normalized", length = MemberNickname.MAX_LENGTH)
    private String previousNicknameNormalized;

    /** 현재 연결된 개인 프로필 이미지의 Object Storage key. null이면 기본 이미지를 사용한다. */
    @Column(name = "profile_image_key", length = 200)
    private String profileImageKey;

    public Member(String name, String phone, String email, MemberRole role) {
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.role = role;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 온보딩 완료를 기록한다. 이미 완료된 회원에게도 같은 결과다. */
    public void completeOnboarding() {
        this.onboardingCompleted = true;
    }

    public boolean hasNickname() {
        return nickname != null;
    }

    /** 댓글·Creator 신청·이벤트 응모처럼 공개 활동을 만들기 전 공개 닉네임 설정을 강제한다. */
    public void requireProfileConfigured() {
        if (!hasNickname()) {
            throw new kr.co.cking.common.exception.BusinessException(MemberErrorCode.PROFILE_SETUP_REQUIRED);
        }
    }

    /** 외부 응답에서 OAuth 제공자 이름을 대신할 공개 표시명이다. */
    public String publicDisplayName() {
        return nickname == null ? NICKNAME_NOT_CONFIGURED : nickname;
    }

    public boolean hasCurrentNickname(MemberNickname requested) {
        return nicknameNormalized != null && nicknameNormalized.equals(requested.normalized());
    }

    /** 최초 설정은 바로 허용하며 변경 간격의 기준 시각을 만들지 않는다. */
    public void setInitialNickname(MemberNickname nickname) {
        if (hasNickname()) {
            throw new IllegalStateException("닉네임이 이미 설정되어 있습니다.");
        }
        this.nickname = nickname.value();
        this.nicknameNormalized = nickname.normalized();
    }

    /** 새 닉네임으로 실제 변경한다. 형식·중복·예약·변경 간격 검증은 Application이 담당한다. */
    public void changeNickname(MemberNickname nickname, LocalDateTime changedAt) {
        if (!hasNickname()) {
            throw new IllegalStateException("최초 설정에는 changeNickname을 사용할 수 없습니다.");
        }
        this.previousNickname = this.nickname;
        this.previousNicknameNormalized = this.nicknameNormalized;
        this.nickname = nickname.value();
        this.nicknameNormalized = nickname.normalized();
        this.nicknameChangedAt = changedAt;
    }

    /** 바로 이전 닉네임으로 복귀한다. 변경 시각을 바꾸지 않아 14일 제한을 우회할 수 없다. */
    public void revertNickname() {
        if (previousNickname == null || previousNicknameNormalized == null) {
            throw new IllegalStateException("복귀할 이전 닉네임이 없습니다.");
        }
        String currentNickname = nickname;
        String currentNormalized = nicknameNormalized;
        this.nickname = previousNickname;
        this.nicknameNormalized = previousNicknameNormalized;
        this.previousNickname = currentNickname;
        this.previousNicknameNormalized = currentNormalized;
    }

    public LocalDateTime nicknameChangeableAt() {
        return nicknameChangedAt == null ? null : nicknameChangedAt.plus(MemberNickname.CHANGE_INTERVAL);
    }

    public boolean canChangeNicknameAt(LocalDateTime now) {
        LocalDateTime changeableAt = nicknameChangeableAt();
        return changeableAt == null || !now.isBefore(changeableAt);
    }

    public void updateProfileImageKey(String profileImageKey) {
        this.profileImageKey = profileImageKey;
    }

    @PrePersist
    private void assignCreatedAtIfMissing() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
