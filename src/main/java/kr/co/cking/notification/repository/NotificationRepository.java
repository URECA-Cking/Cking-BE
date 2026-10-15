package kr.co.cking.notification.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import kr.co.cking.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Notification 영속화와 사용자별 조회·읽음 처리용 잠금 조회를 제공한다. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 특정 Member가 소유한 Notification 중 숨기지 않았고 보관 Event에 속하지 않은 항목만 조회한다. */
    @Query("""
            select notification from Notification notification
            left join kr.co.cking.event.domain.Event event on event.eventId = notification.eventId
            where notification.memberId = :memberId
              and notification.hiddenAt is null
              and (notification.eventId is null or (event.deletedAt is null and event.archivedAt is null))
            """)
    Page<Notification> findVisibleByMemberId(@Param("memberId") Long memberId, Pageable pageable);

    /** @deprecated 보관 Event까지 숨기는 visible 조회를 사용한다. */
    @Deprecated
    default Page<Notification> findByMemberIdAndHiddenAtIsNull(Long memberId, Pageable pageable) {
        return findVisibleByMemberId(memberId, pageable);
    }

    /** 동시 읽음 처리의 최초 시각을 하나로 보장하기 위해 Notification 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select notification from Notification notification
            left join kr.co.cking.event.domain.Event event on event.eventId = notification.eventId
            where notification.notificationId = :notificationId
              and (notification.eventId is null or (event.deletedAt is null and event.archivedAt is null))
            """)
    Optional<Notification> findVisibleByIdForUpdate(@Param("notificationId") Long notificationId);

    /** @deprecated 보관 Event까지 숨기는 visible 잠금 조회를 사용한다. */
    @Deprecated
    default Optional<Notification> findByIdForUpdate(Long notificationId) {
        return findVisibleByIdForUpdate(notificationId);
    }

    /**
     * Drawing이 사용한 Snapshot 후보 중 해당 Drawing의 Winner가 아닌 Member에게 미당첨 알림을 한 번의 INSERT ... SELECT로 저장한다.
     * 대규모 응모자에서도 엔티티 적재와 건별 INSERT 없이 DB 안에서 처리하며, 호출자의 트랜잭션에 참여한다.
     * 같은 이벤트·Member·유형의 알림이 이미 있으면 유니크 제약이 거부하므로 최초 공개에서만 호출해야 한다.
     *
     * @return 저장한 알림 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO notification (member_id, event_id, drawing_id, type, title, body, created_at)
            SELECT c.member_id, d.event_id, d.id, :type, :title, :body, :createdAt
            FROM drawing d
            JOIN draw_snapshot_candidate c ON c.snapshot_id = d.snapshot_id
            WHERE d.id = :drawingId
              AND NOT EXISTS (
                  SELECT 1 FROM winner w WHERE w.drawing_id = d.id AND w.member_id = c.member_id
              )
            ORDER BY c.member_id
            """, nativeQuery = true)
    int insertNonWinnerNotifications(
            @Param("drawingId") Long drawingId,
            @Param("type") String type,
            @Param("title") String title,
            @Param("body") String body,
            @Param("createdAt") Instant createdAt);

    /** 요청 Member의 숨기지 않았고 아직 읽지 않은 모든 Notification에 최초 읽음 시각을 기록한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification notification set notification.readAt = :readAt "
            + "where notification.memberId = :memberId and notification.readAt is null and notification.hiddenAt is null "
            + "and (notification.eventId is null or notification.eventId in "
            + "(select event.eventId from Event event where event.deletedAt is null and event.archivedAt is null))")
    int markAllUnreadAsRead(@Param("memberId") Long memberId, @Param("readAt") Instant readAt);
}
