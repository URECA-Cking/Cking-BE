package kr.co.cking.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 크리에이터가 직접 등록하는 일정(팬사인회·생일·방송·콘텐츠 공개 등).
 * 추첨용 {@code Event}와 달리 승인 절차나 응모 상태가 없고, 생성 즉시 공개된다.
 * Snapshot·Drawing·Winner로 이어지는 하류 감사 이력이 없어 소프트 삭제 없이 하드 삭제한다.
 */
@Entity
@Table(name = "creator_schedule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 30)
    private ScheduleType scheduleType;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    /** IANA Zone ID. 검증·정규화는 Service 계층({@code CreatorScheduleService})에서 수행한다. */
    @Column(name = "time_zone", nullable = false, length = 50)
    private String timeZone;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "external_url", length = 500)
    private String externalUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CreatorSchedule(
            Long creatorId,
            ScheduleType scheduleType,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            String timeZone,
            String location,
            String imageUrl,
            String externalUrl,
            Instant now
    ) {
        this.creatorId = creatorId;
        this.createdAt = now;
        apply(scheduleType, title, description, startAt, endAt, timeZone, location, imageUrl, externalUrl, now);
    }

    /** 전체 필드를 새 값으로 교체한다(부분 patch 아님). */
    public void update(
            ScheduleType scheduleType,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            String timeZone,
            String location,
            String imageUrl,
            String externalUrl,
            Instant now
    ) {
        apply(scheduleType, title, description, startAt, endAt, timeZone, location, imageUrl, externalUrl, now);
    }

    private void apply(
            ScheduleType scheduleType,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            String timeZone,
            String location,
            String imageUrl,
            String externalUrl,
            Instant now
    ) {
        this.scheduleType = scheduleType;
        this.title = title;
        this.description = description;
        this.startAt = startAt;
        this.endAt = endAt;
        this.timeZone = timeZone;
        this.location = location;
        this.imageUrl = imageUrl;
        this.externalUrl = externalUrl;
        this.updatedAt = now;
    }
}
