package kr.co.cking.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;
import kr.co.cking.sanction.domain.MemberSanction;
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
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

    @Autowired
    private MemberSanctionRepository sanctionRepository;

    @Test
    void Member별_알림을_생성시각과_알림_ID_내림차순으로_조회한다() {
        Fixture fixture = fixture();
        Notification first = saveNotification(
                fixture.memberId(), fixture.eventId(), fixture.drawingId(), fixture.memberWinnerId(),
                NotificationType.INITIAL_WINNER, "먼저 생성된 알림", Instant.parse("2026-09-17T00:00:00Z"), null
        );
        Notification second = saveNotification(
                fixture.memberId(), fixture.eventId(), fixture.drawingId(), fixture.memberWinnerId(),
                NotificationType.REDRAW_WINNER, "나중에 생성된 알림", Instant.parse("2026-09-17T00:00:00Z"),
                Instant.parse("2026-09-17T01:00:00Z")
        );
        saveNotification(
                fixture.otherMemberId(), fixture.eventId(), fixture.drawingId(), fixture.otherMemberWinnerId(),
                NotificationType.INITIAL_WINNER, "다른 사용자의 알림", Instant.parse("2026-09-18T00:00:00Z"), null
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

    /** 전체 읽음 UPDATE가 페이지 밖의 미확인 알림까지 처리하고 기존 시각과 타인 알림은 보존하는지 검증한다. */
    @Test
    void 전체_읽음은_페이지와_무관하게_미확인_알림만_변경한다() {
        Fixture fixture = fixture();
        Instant previousReadAt = Instant.parse("2026-09-17T01:00:00Z");
        Instant bulkReadAt = Instant.parse("2026-10-15T01:00:00Z");
        Notification unreadWinner = saveNotification(
                fixture.memberId(), fixture.eventId(), fixture.drawingId(), fixture.memberWinnerId(),
                NotificationType.INITIAL_WINNER, "미확인 당첨 알림", Instant.parse("2026-09-17T00:00:00Z"), null);
        Notification alreadyRead = saveNotification(
                fixture.memberId(), fixture.eventId(), fixture.drawingId(), fixture.memberWinnerId(),
                NotificationType.REDRAW_WINNER, "기존 읽음 알림", Instant.parse("2026-09-18T00:00:00Z"), previousReadAt);
        long sanctionId = saveSanction(fixture.memberId());
        Notification unreadSanction = notificationRepository.saveAndFlush(Notification.forSanction(
                fixture.memberId(), sanctionId, NotificationType.COMMENT_RESTRICTION_STARTED, "미확인 제재 알림", "본문"));
        Notification otherMember = saveNotification(
                fixture.otherMemberId(), fixture.eventId(), fixture.drawingId(), fixture.otherMemberWinnerId(),
                NotificationType.INITIAL_WINNER, "타인 알림", Instant.parse("2026-09-19T00:00:00Z"), null);
        assertThat(notificationRepository.findByMemberId(fixture.memberId(),
                PageRequest.of(0, 1, NOTIFICATION_LIST_SORT)).getContent()).hasSize(1);
        entityManager.clear();

        assertThat(notificationRepository.markAllUnreadAsRead(fixture.memberId(), bulkReadAt)).isEqualTo(2);
        assertThat(notificationRepository.markAllUnreadAsRead(fixture.memberId(), bulkReadAt.plusSeconds(1))).isZero();
        entityManager.clear();

        assertThat(notificationRepository.findById(unreadWinner.getNotificationId()).orElseThrow().getReadAt())
                .isEqualTo(bulkReadAt);
        assertThat(notificationRepository.findById(unreadSanction.getNotificationId()).orElseThrow().getReadAt())
                .isEqualTo(bulkReadAt);
        assertThat(notificationRepository.findById(alreadyRead.getNotificationId()).orElseThrow().getReadAt())
                .isEqualTo(previousReadAt);
        assertThat(notificationRepository.findById(otherMember.getNotificationId()).orElseThrow().getReadAt())
                .isNull();
    }

    @Test
    void 제재_알림은_이벤트_추첨_당첨_없이_저장되고_조회된다() {
        long memberId = insertMember("제재 알림 회원");
        long sanctionId = saveSanction(memberId);

        Notification saved = notificationRepository.saveAndFlush(Notification.forSanction(
                memberId, sanctionId, NotificationType.COMMENT_RESTRICTION_STARTED, "댓글 작성 제한 안내", "본문"));
        entityManager.clear();

        Notification found = notificationRepository.findById(saved.getNotificationId()).orElseThrow();
        assertThat(found.getSanctionId()).isEqualTo(sanctionId);
        assertThat(found.getType()).isEqualTo(NotificationType.COMMENT_RESTRICTION_STARTED);
        assertThat(found.getEventId()).isNull();
        assertThat(found.getDrawingId()).isNull();
        assertThat(found.getWinnerId()).isNull();
        assertThat(notificationRepository.findByMemberId(memberId, PageRequest.of(0, 20, NOTIFICATION_LIST_SORT))
                .getContent()).extracting(Notification::getNotificationId).containsExactly(saved.getNotificationId());
    }

    @Test
    void 같은_제재의_같은_종류_알림은_유니크_제약이_막고_다른_종류는_허용한다() {
        long memberId = insertMember("제재 알림 중복 회원");
        long sanctionId = saveSanction(memberId);
        notificationRepository.saveAndFlush(Notification.forSanction(
                memberId, sanctionId, NotificationType.COMMENT_RESTRICTION_STARTED, "제목", "본문"));

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(Notification.forSanction(
                memberId, sanctionId, NotificationType.COMMENT_RESTRICTION_STARTED, "제목", "본문")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_제재라도_해제_알림은_따로_저장할_수_있다() {
        long memberId = insertMember("제재 해제 알림 회원");
        long sanctionId = saveSanction(memberId);
        notificationRepository.saveAndFlush(Notification.forSanction(
                memberId, sanctionId, NotificationType.COMMENT_RESTRICTION_STARTED, "제목", "본문"));

        Notification released = notificationRepository.saveAndFlush(Notification.forSanction(
                memberId, sanctionId, NotificationType.COMMENT_RESTRICTION_RELEASED, "제목", "본문"));

        assertThat(released.getNotificationId()).isNotNull();
    }

    @Test
    void 이벤트_추첨_당첨_중_일부만_채우면_CHECK_제약이_거부한다() {
        Fixture fixture = fixture();

        // winner_id만 비어 있어도, 복합 FK는 NULL이 있으면 검사되지 않으므로 CHECK가 막아야 한다.
        assertThatThrownBy(() -> notificationRepository.saveAndFlush(new Notification(
                fixture.memberId(), fixture.eventId(), fixture.drawingId(), null,
                NotificationType.INITIAL_WINNER, "제목", "본문")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 존재하지_않는_제재를_가리키는_알림은_FK가_거부한다() {
        long memberId = insertMember("없는 제재 회원");

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(Notification.forSanction(
                memberId, 999_999_999L, NotificationType.COMMENT_RESTRICTION_STARTED, "제목", "본문")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long saveSanction(long memberId) {
        long adminId = insertMember("제재 알림 관리자");
        return sanctionRepository.saveAndFlush(MemberSanction.manual(
                memberId, adminId, "사유", Instant.parse("2026-10-13T00:00:00Z"), Duration.ofDays(1))).getSanctionId();
    }

    /** Winner의 실제 계보와 일치하는 Notification을 지정한 시각으로 저장한다. */
    private Notification saveNotification(
            long memberId,
            long eventId,
            long drawingId,
            long winnerId,
            NotificationType type,
            String title,
            Instant createdAt,
            Instant readAt
    ) {
        Notification notification = new Notification(
                memberId, eventId, drawingId, winnerId, type, title, "알림 본문"
        );
        ReflectionTestUtils.setField(notification, "createdAt", createdAt);
        ReflectionTestUtils.setField(notification, "readAt", readAt);
        return notificationRepository.saveAndFlush(notification);
    }

    private Fixture fixture() {
        long memberId = insertMember("회원1");
        long otherMemberId = insertMember("회원2");
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
                insertWinner(eventId, drawingId, otherMemberId, 2)
        );
    }

    private long insertMember(String name) {
        entityManager.createNativeQuery("INSERT INTO member (name, role) VALUES (:name, 'USER')")
                .setParameter("name", name)
                .executeUpdate();
        return lastInsertId();
    }

    private long insertCreator(long memberId) {
        entityManager.createNativeQuery("INSERT INTO creator (member_id, name, activity_name, activity_name_normalized, activity_name_configured) VALUES (:memberId, '테스트크리에이터', (@creator_fixture_activity_name := CONCAT('c', SUBSTRING(MD5(UUID()), 1, 19))), @creator_fixture_activity_name, true)")
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
            long memberWinnerId,
            long otherMemberWinnerId
    ) {
    }
}
