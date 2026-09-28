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

/**
 * Creator가 버린 이전 slug를 일정 기간 다른 Creator가 쓰지 못하게 잡아 둔다(이슈 #301).
 * 기존 공유 링크가 곧바로 다른 Creator의 Space를 열지 않게 하기 위해서다.
 */
@Entity
@Table(
        name = "creator_space_slug_reservation",
        uniqueConstraints = @UniqueConstraint(name = "uk_creator_space_slug_reservation_slug", columnNames = "slug")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorSpaceSlugReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "slug", nullable = false, length = 100)
    private String slug;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private CreatorSpaceSlugReservation(String slug, Long creatorId, LocalDateTime expiresAt, LocalDateTime createdAt) {
        this.slug = slug;
        this.creatorId = creatorId;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public static CreatorSpaceSlugReservation reserve(String slug, Long creatorId, LocalDateTime now) {
        return new CreatorSpaceSlugReservation(slug, creatorId, now.plus(CreatorSpaceCustomSlug.RESERVATION_PERIOD), now);
    }

    /** 만료된 예약 행을 다시 쓸 때 주인과 기간을 새로 잡는다. */
    public void renew(Long creatorId, LocalDateTime now) {
        this.creatorId = creatorId;
        this.expiresAt = now.plus(CreatorSpaceCustomSlug.RESERVATION_PERIOD);
        this.createdAt = now;
    }

    public boolean isActiveAt(LocalDateTime now) {
        return now.isBefore(expiresAt);
    }

    /** 예약 기간 안에서 다른 Creator가 이 slug를 쓰려는 경우만 막는다. 예약한 본인은 되돌릴 수 있다. */
    public boolean blocks(Long creatorId, LocalDateTime now) {
        return isActiveAt(now) && !this.creatorId.equals(creatorId);
    }
}
