package kr.co.cking.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class NotificationRepositoryJpaTest {

    private static final Sort NOTIFICATION_LIST_SORT = Sort.by(
            Sort.Direction.DESC, "createdAt", "notificationId"
    );

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void Member별_알림을_생성시각과_알림_ID_내림차순으로_조회한다() {
        Fixture fixture = fixture();
        Notification first = saveNotification(
                fixture.memberId(), fixture.eventId(), fixture.drawingId(), fixture.firstWinnerId(),
                "먼저 생성된 알림", Instant.parse("2026-09-17T00:00:00Z"), null
        );
        Notification second = saveNotification(
                fixture.memberId(), fixture.eventId(), fixture.drawingId(), fixture.secondWinnerId(),
                "나중에 생성된 알림", Instant.parse("2026-09-17T00:00:00Z"),
                Instant.parse("2026-09-17T01:00:00Z")
        );
        saveNotification(
                fixture.otherMemberId(), fixture.eventId(), fixture.drawingId(), fixture.thirdWinnerId(),
                "다른 사용자의 알림", Instant.parse("2026-09-18T00:00:00Z"), null
        );
        entityManager.clear();

        List<Notification> result = notificationRepository.findByMemberId(
                fixture.memberId(), PageRequest.of(0, 20, NOTIFICATION_LIST_SORT)
        ).getContent();

        assertThat(result).extracting(Notification::getNotificationId)
                .containsExactly(second.getNotificationId(), first.getNotificationId());
        assertThat(result).extracting(Notification::getTitle)
                .containsExactly("나중에 생성된 알림", "먼저 생성된 알림");
        assertThat(result.getFirst().getReadAt()).isEqualTo(Instant.parse("2026-09-17T01:00:00Z"));
    }

    private Notification saveNotification(
            long memberId,
            long eventId,
            long drawingId,
            long winnerId,
            String title,
            Instant createdAt,
            Instant readAt
    ) {
        Notification notification = new Notification(
                memberId, eventId, drawingId, winnerId, NotificationType.INITIAL_WINNER, title, "알림 본문"
        );
        ReflectionTestUtils.setField(notification, "createdAt", createdAt);
        ReflectionTestUtils.setField(notification, "readAt", readAt);
        return notificationRepository.saveAndFlush(notification);
    }

    private Fixture fixture() {
        long memberId = insertMember("회원1");
        long otherMemberId = insertMember("회원2");
        long thirdMemberId = insertMember("회원3");
        long creatorId = insertCreator(memberId);
        long eventId = insertEvent(creatorId, memberId);
        long snapshotId = insertSnapshot(eventId);
        long drawingId = insertDrawing(eventId, snapshotId, insertSeed(), memberId);
        return new Fixture(
                memberId,
                otherMemberId,
                eventId,
                drawingId,
                insertWinner(eventId, drawingId, memberId, 1),
                insertWinner(eventId, drawingId, otherMemberId, 2),
                insertWinner(eventId, drawingId, thirdMemberId, 3)
        );
    }

    private long insertMember(String name) {
        entityManager.createNativeQuery("INSERT INTO member (name, role) VALUES (:name, 'USER')")
                .setParameter("name", name)
                .executeUpdate();
        return lastInsertId();
    }

    private long insertCreator(long memberId) {
        entityManager.createNativeQuery("INSERT INTO creator (member_id, name) VALUES (:memberId, '테스트크리에이터')")
                .setParameter("memberId", memberId)
                .executeUpdate();
        return lastInsertId();
    }

    private long insertEvent(long creatorId, long createdBy) {
        entityManager.createNativeQuery("""
                        INSERT INTO event (
                            creator_id, title, start_at, end_at, winner_count, draw_method,
                            status, created_by, request_id
                        ) VALUES (
                            :creatorId, '테스트 이벤트', :startAt, :endAt, 3, 'WEIGHTED',
                            'CLOSED', :createdBy, :requestId
                        )
                        """)
                .setParameter("creatorId", creatorId)
                .setParameter("startAt", Instant.parse("2026-09-01T00:00:00Z"))
                .setParameter("endAt", Instant.parse("2026-09-15T00:00:00Z"))
                .setParameter("createdBy", createdBy)
                .setParameter("requestId", UUID.randomUUID().toString())
                .executeUpdate();
        return lastInsertId();
    }

    private long insertSnapshot(long eventId) {
        entityManager.createNativeQuery("""
                        INSERT INTO draw_snapshot (
                            event_id, candidate_count, total_ticket_count, winner_count,
                            draw_method, algorithm_version, snapshot_hash, verification_status
                        ) VALUES (
                            :eventId, 0, 0, 3, 'WEIGHTED', 'WEIGHTED_V1', :snapshotHash, 'UNVERIFIED'
                        )
                        """)
                .setParameter("eventId", eventId)
                .setParameter("snapshotHash", UUID.randomUUID().toString().replace("-", "").repeat(2))
                .executeUpdate();
        return lastInsertId();
    }

    private long insertSeed() {
        entityManager.createNativeQuery("INSERT INTO draw_seed (seed_value) VALUES (:seedValue)")
                .setParameter("seedValue", new byte[]{1, 2, 3})
                .executeUpdate();
        return lastInsertId();
    }

    private long insertDrawing(long eventId, long snapshotId, long seedId, long requestedBy) {
        entityManager.createNativeQuery("""
                        INSERT INTO drawing (
                            event_id, draw_no, draw_type, snapshot_id, seed_id, draw_method,
                            algorithm_version, winner_count, status, visibility, requested_by
                        ) VALUES (
                            :eventId, 0, 'INITIAL', :snapshotId, :seedId, 'WEIGHTED',
                            'WEIGHTED_V1', 3, 'COMPLETED', 'PUBLIC', :requestedBy
                        )
                        """)
                .setParameter("eventId", eventId)
                .setParameter("snapshotId", snapshotId)
                .setParameter("seedId", seedId)
                .setParameter("requestedBy", requestedBy)
                .executeUpdate();
        return lastInsertId();
    }

    private long insertWinner(long eventId, long drawingId, long memberId, int rank) {
        entityManager.createNativeQuery("""
                        INSERT INTO winner (event_id, drawing_id, member_id, rank_in_drawing, applied_ticket_count)
                        VALUES (:eventId, :drawingId, :memberId, :rank, 1)
                        """)
                .setParameter("eventId", eventId)
                .setParameter("drawingId", drawingId)
                .setParameter("memberId", memberId)
                .setParameter("rank", rank)
                .executeUpdate();
        return lastInsertId();
    }

    private long lastInsertId() {
        return ((Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private record Fixture(
            long memberId,
            long otherMemberId,
            long eventId,
            long drawingId,
            long firstWinnerId,
            long secondWinnerId,
            long thirdWinnerId
    ) {
    }
}
