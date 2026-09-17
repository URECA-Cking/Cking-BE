package kr.co.cking.event.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 확정 응모 기록. {@code uk_entry_request}(requestId)가 SPEND Stream 재전달의
 * 멱등성 판정 기준이다(DB 스키마 찐 최종 §10). creatorId는 저장하지 않고
 * {@code Event.creatorId}를 통해 판단한다(CLAUDE.md §11 DB 스키마 계약).
 */
@Entity
@Table(name = "event_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long entryId;

    private Long memberId;
    private Long eventId;
    private String requestId;
    private Long usedTicketCount;
    private Instant appliedAt;

    @Builder
    private EventEntry(Long memberId, Long eventId, String requestId, Long usedTicketCount, Instant appliedAt) {
        this.memberId = memberId;
        this.eventId = eventId;
        this.requestId = requestId;
        this.usedTicketCount = usedTicketCount;
        this.appliedAt = appliedAt;
    }
}
