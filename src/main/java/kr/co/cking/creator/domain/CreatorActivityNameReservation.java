package kr.co.cking.creator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 변경으로 버린 Creator 활동명을 14일간 해당 Creator만 복귀할 수 있게 예약한다. */
@Entity
@Table(name = "creator_activity_name_reservation", uniqueConstraints = @UniqueConstraint(
        name = "uk_creator_activity_name_reservation_normalized", columnNames = "activity_name_normalized"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorActivityNameReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "activity_name", nullable = false, length = CreatorActivityName.MAX_LENGTH)
    private String activityName;

    @Column(name = "activity_name_normalized", nullable = false, length = CreatorActivityName.MAX_LENGTH)
    private String activityNameNormalized;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private CreatorActivityNameReservation(CreatorActivityName activityName, Long creatorId, LocalDateTime now) {
        renew(activityName, creatorId, now);
    }

    public static CreatorActivityNameReservation reserve(CreatorActivityName activityName, Long creatorId, LocalDateTime now) {
        return new CreatorActivityNameReservation(activityName, creatorId, now);
    }

    public void renew(CreatorActivityName activityName, Long creatorId, LocalDateTime now) {
        this.activityName = activityName.value();
        this.activityNameNormalized = activityName.normalized();
        this.creatorId = creatorId;
        this.expiresAt = now.plus(CreatorActivityName.RESERVATION_PERIOD);
        this.createdAt = now;
    }

    public boolean isOwnedBy(Long creatorId, LocalDateTime now) {
        return now.isBefore(expiresAt) && this.creatorId.equals(creatorId);
    }

    public boolean blocks(Long creatorId, LocalDateTime now) {
        return now.isBefore(expiresAt) && !this.creatorId.equals(creatorId);
    }

    public boolean isActiveAt(LocalDateTime now) {
        return now.isBefore(expiresAt);
    }
}
