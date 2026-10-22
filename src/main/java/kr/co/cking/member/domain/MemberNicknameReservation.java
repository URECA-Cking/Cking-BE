package kr.co.cking.member.domain;

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

/** 변경으로 버린 닉네임을 일정 기간 기존 회원에게만 돌려줄 수 있게 잡아 둔다. */
@Entity
@Table(name = "member_nickname_reservation", uniqueConstraints = @UniqueConstraint(
        name = "uk_member_nickname_reservation_normalized", columnNames = "nickname_normalized"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberNicknameReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "nickname", nullable = false, length = MemberNickname.MAX_LENGTH)
    private String nickname;

    @Column(name = "nickname_normalized", nullable = false, length = MemberNickname.MAX_LENGTH)
    private String nicknameNormalized;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private MemberNicknameReservation(MemberNickname nickname, Long memberId, LocalDateTime now) {
        this.nickname = nickname.value();
        this.nicknameNormalized = nickname.normalized();
        this.memberId = memberId;
        this.expiresAt = now.plus(MemberNickname.RESERVATION_PERIOD);
        this.createdAt = now;
    }

    public static MemberNicknameReservation reserve(MemberNickname nickname, Long memberId, LocalDateTime now) {
        return new MemberNicknameReservation(nickname, memberId, now);
    }

    public void renew(MemberNickname nickname, Long memberId, LocalDateTime now) {
        this.nickname = nickname.value();
        this.nicknameNormalized = nickname.normalized();
        this.memberId = memberId;
        this.expiresAt = now.plus(MemberNickname.RESERVATION_PERIOD);
        this.createdAt = now;
    }

    public boolean isActiveAt(LocalDateTime now) {
        return now.isBefore(expiresAt);
    }

    public boolean isOwnedBy(Long memberId, LocalDateTime now) {
        return isActiveAt(now) && this.memberId.equals(memberId);
    }

    public boolean blocks(Long memberId, LocalDateTime now) {
        return isActiveAt(now) && !this.memberId.equals(memberId);
    }
}
