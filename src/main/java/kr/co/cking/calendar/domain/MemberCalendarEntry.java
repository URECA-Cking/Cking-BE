package kr.co.cking.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 사용자가 개인 캘린더에 담은 {@link CreatorSchedule} 참조.
 * 일정 내용을 복사하지 않고 {@code scheduleId}만 참조하므로, 크리에이터가 일정을 수정하면
 * 개인 캘린더에도 최신 값이 그대로 보인다.
 */
@Entity
@Table(name = "member_calendar_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCalendarEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public MemberCalendarEntry(Long memberId, Long scheduleId, Instant createdAt) {
        this.memberId = memberId;
        this.scheduleId = scheduleId;
        this.createdAt = createdAt;
    }
}
