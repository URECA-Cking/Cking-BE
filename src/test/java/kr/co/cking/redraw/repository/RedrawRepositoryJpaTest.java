package kr.co.cking.redraw.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequestStatus;
import kr.co.cking.drawing.repository.RedrawDrawingQueryRepository;
import kr.co.cking.drawing.repository.RedrawExclusionSource;
import kr.co.cking.drawing.repository.RedrawVacancyPrizeSource;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/** Redraw 후보·점유·Drawing 전용 조회가 MySQL에서 의도한 결원만 읽는지 검증한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(RedrawDrawingQueryRepository.class)
class RedrawRepositoryJpaTest {

    @Autowired
    private RedrawVacancyCandidateRepository redrawVacancyCandidateRepository;

    @Autowired
    private RedrawRequestVacancyRepository redrawRequestVacancyRepository;

    @Autowired
    private RedrawDrawingQueryRepository redrawDrawingQueryRepository;

    @Autowired
    private EntityManager entityManager;

    /** 원본 Drawing의 DECLINED·DISQUALIFIED Winner만 ID순으로 결원 후보로 반환한다. */
    @Test
    void 원본_Drawing의_결원_Winner만_ID순으로_조회한다() {
        Fixture fixture = fixture();
        long declinedWinnerId = insertWinner(
                fixture.eventId(), fixture.initialDrawingId(), 1, WinnerManagementStatus.DECLINED);
        long disqualifiedWinnerId = insertWinner(
                fixture.eventId(), fixture.initialDrawingId(), 2, WinnerManagementStatus.DISQUALIFIED);
        insertWinner(fixture.eventId(), fixture.initialDrawingId(), 3, WinnerManagementStatus.SELECTED);
        long otherEventId = insertEvent(fixture.creatorId(), fixture.adminId());
        long otherEventDrawingId = insertInitialDrawing(
                otherEventId,
                insertSnapshot(otherEventId),
                insertSeed(),
                fixture.adminId()
        );
        insertWinner(otherEventId, otherEventDrawingId, 1, WinnerManagementStatus.DECLINED);

        List<Long> winnerIds = redrawVacancyCandidateRepository.findVacancyWinnerIds(
                fixture.eventId(),
                fixture.initialDrawingId(),
                List.of(WinnerManagementStatus.DECLINED, WinnerManagementStatus.DISQUALIFIED)
        );

        assertThat(winnerIds).containsExactly(declinedWinnerId, disqualifiedWinnerId);
    }

    /** REDRAW 제외 명단 원본은 해당 Event의 기존 Winner를 Member ID순으로 읽는다. */
    @Test
    void REDRAW_제외_명단_원본을_Event의_Winner_순서대로_조회한다() {
        Fixture fixture = fixture();
        insertWinner(fixture.eventId(), fixture.initialDrawingId(), 1, WinnerManagementStatus.SELECTED);
        insertWinner(fixture.eventId(), fixture.initialDrawingId(), 2, WinnerManagementStatus.DECLINED);

        List<RedrawExclusionSource> sources = redrawDrawingQueryRepository
                .findExclusionSourcesByEventId(fixture.eventId());

        assertThat(sources).extracting(RedrawExclusionSource::managementStatus)
                .containsExactly(WinnerManagementStatus.SELECTED, WinnerManagementStatus.DECLINED);
    }

    /** 진행 중 요청과 EXECUTED 요청의 Winner를 점유 목록으로 반환한다. */
    @Test
    void 진행_중_또는_실행_완료된_요청이_점유한_Winner를_조회한다() {
        Fixture fixture = fixture();
        long requestedWinnerId = insertWinner(
                fixture.eventId(), fixture.initialDrawingId(), 1, WinnerManagementStatus.DECLINED);
        long approvedWinnerId = insertWinner(
                fixture.eventId(), fixture.initialDrawingId(), 2, WinnerManagementStatus.DISQUALIFIED);
        long rejectedWinnerId = insertWinner(
                fixture.eventId(), fixture.initialDrawingId(), 3, WinnerManagementStatus.DECLINED);
        long executedWinnerId = insertWinner(
                fixture.eventId(), fixture.initialDrawingId(), 4, WinnerManagementStatus.DISQUALIFIED);
        long failedWinnerId = insertWinner(
                fixture.eventId(), fixture.initialDrawingId(), 5, WinnerManagementStatus.DECLINED);
        long insufficientWinnerId = insertWinner(
                fixture.eventId(), fixture.initialDrawingId(), 6, WinnerManagementStatus.DISQUALIFIED);

        insertVacancy(
                insertRedrawRequest(fixture, RedrawRequestStatus.REQUESTED, RedrawExecutionStatus.PENDING),
                requestedWinnerId
        );
        insertVacancy(
                insertRedrawRequest(fixture, RedrawRequestStatus.APPROVED, RedrawExecutionStatus.PENDING),
                approvedWinnerId
        );
        insertVacancy(
                insertRedrawRequest(fixture, RedrawRequestStatus.REJECTED, RedrawExecutionStatus.PENDING),
                rejectedWinnerId
        );
        insertVacancy(
                insertRedrawRequest(fixture, RedrawRequestStatus.APPROVED, RedrawExecutionStatus.EXECUTED),
                executedWinnerId
        );
        insertVacancy(
                insertRedrawRequest(fixture, RedrawRequestStatus.APPROVED, RedrawExecutionStatus.FAILED),
                failedWinnerId
        );
        insertVacancy(
                insertRedrawRequest(fixture, RedrawRequestStatus.APPROVED, RedrawExecutionStatus.INSUFFICIENT_CANDIDATES),
                insufficientWinnerId
        );

        List<Long> occupiedWinnerIds = redrawRequestVacancyRepository.findOccupiedWinnerIds(List.of(
                requestedWinnerId, approvedWinnerId, rejectedWinnerId, executedWinnerId, failedWinnerId, insufficientWinnerId
        ));

        assertThat(occupiedWinnerIds).containsExactlyInAnyOrder(requestedWinnerId, approvedWinnerId, executedWinnerId);
    }

    /** 고정 결원 Winner의 상품 원본은 RedrawRequestVacancy 생성 순서대로 조회한다. */
    @Test
    void 고정_결원_Winner의_상품_원본을_결원_순서대로_조회한다() {
        Fixture fixture = fixture();
        long firstWinnerId = insertWinner(fixture.eventId(), fixture.initialDrawingId(), 1, WinnerManagementStatus.DECLINED);
        long secondWinnerId = insertWinner(fixture.eventId(), fixture.initialDrawingId(), 2, WinnerManagementStatus.DISQUALIFIED);
        long redrawRequestId = insertRedrawRequest(fixture, RedrawRequestStatus.APPROVED, RedrawExecutionStatus.PENDING);
        insertVacancy(redrawRequestId, secondWinnerId);
        insertVacancy(redrawRequestId, firstWinnerId);

        List<RedrawVacancyPrizeSource> sources = redrawDrawingQueryRepository
                .findVacancyPrizeSourcesByRequestId(redrawRequestId);

        assertThat(sources).extracting(RedrawVacancyPrizeSource::winnerId)
                .containsExactly(secondWinnerId, firstWinnerId);
    }

    /** JPA 조회 테스트에 필요한 Event와 최초 Drawing을 저장한다. */
    private Fixture fixture() {
        long adminId = insertMember("관리자", "ADMIN");
        long creatorId = insertCreator(adminId);
        long eventId = insertEvent(creatorId, adminId);
        long snapshotId = insertSnapshot(eventId);
        long initialDrawingId = insertInitialDrawing(eventId, snapshotId, insertSeed(), adminId);
        return new Fixture(adminId, creatorId, eventId, initialDrawingId);
    }

    private long insertMember(String name, String role) {
        entityManager.createNativeQuery("INSERT INTO member (name, role) VALUES (:name, :role)")
                .setParameter("name", name + UUID.randomUUID().toString().substring(0, 8))
                .setParameter("role", role)
                .executeUpdate();
        return lastInsertId();
    }

    private long insertCreator(long memberId) {
        entityManager.createNativeQuery(
                        "INSERT INTO creator (member_id, name) VALUES (:memberId, '테스트크리에이터')")
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
                            :creatorId, '테스트 이벤트', :startAt, :endAt, 4, 'WEIGHTED',
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
                            :eventId, 0, 0, 4, 'WEIGHTED', 'WEIGHTED_V1', :snapshotHash, 'UNVERIFIED'
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

    private long insertInitialDrawing(long eventId, long snapshotId, long seedId, long requestedBy) {
        entityManager.createNativeQuery("""
                        INSERT INTO drawing (
                            event_id, draw_no, draw_type, snapshot_id, seed_id, draw_method,
                            algorithm_version, winner_count, status, visibility, requested_by
                        ) VALUES (
                            :eventId, 0, 'INITIAL', :snapshotId, :seedId, 'WEIGHTED',
                            'WEIGHTED_V1', 4, 'COMPLETED', 'PUBLIC', :requestedBy
                        )
                        """)
                .setParameter("eventId", eventId)
                .setParameter("snapshotId", snapshotId)
                .setParameter("seedId", seedId)
                .setParameter("requestedBy", requestedBy)
                .executeUpdate();
        return lastInsertId();
    }

    private long insertWinner(long eventId, long drawingId, int rank, WinnerManagementStatus status) {
        long memberId = insertMember("후보", "USER");
        entityManager.createNativeQuery("""
                        INSERT INTO winner (event_id, drawing_id, member_id, rank_in_drawing, applied_ticket_count)
                        VALUES (:eventId, :drawingId, :memberId, :rank, 1)
                        """)
                .setParameter("eventId", eventId)
                .setParameter("drawingId", drawingId)
                .setParameter("memberId", memberId)
                .setParameter("rank", rank)
                .executeUpdate();
        long winnerId = lastInsertId();
        entityManager.createNativeQuery("""
                        INSERT INTO winner_management (winner_id, status)
                        VALUES (:winnerId, :status)
                        """)
                .setParameter("winnerId", winnerId)
                .setParameter("status", status.name())
                .executeUpdate();
        return winnerId;
    }

    private long insertRedrawRequest(
            Fixture fixture,
            RedrawRequestStatus status,
            RedrawExecutionStatus executionStatus
    ) {
        entityManager.createNativeQuery("""
                        INSERT INTO redraw_request (
                            event_id, original_drawing_id, vacancy_count, idempotency_key,
                            status, execution_status, requested_by, requested_at
                        ) VALUES (
                            :eventId, :originalDrawingId, 1, :idempotencyKey,
                            :status, :executionStatus, :requestedBy, :requestedAt
                        )
                        """)
                .setParameter("eventId", fixture.eventId())
                .setParameter("originalDrawingId", fixture.initialDrawingId())
                .setParameter("idempotencyKey", UUID.randomUUID().toString())
                .setParameter("status", status.name())
                .setParameter("executionStatus", executionStatus.name())
                .setParameter("requestedBy", fixture.adminId())
                .setParameter("requestedAt", Instant.parse("2026-09-16T00:00:00Z"))
                .executeUpdate();
        return lastInsertId();
    }

    private void insertVacancy(long redrawRequestId, long winnerId) {
        entityManager.createNativeQuery("""
                        INSERT INTO redraw_request_vacancy (redraw_request_id, winner_id)
                        VALUES (:redrawRequestId, :winnerId)
                        """)
                .setParameter("redrawRequestId", redrawRequestId)
                .setParameter("winnerId", winnerId)
                .executeUpdate();
    }

    private long lastInsertId() {
        Number id = (Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult();
        return id.longValue();
    }

    private record Fixture(long adminId, long creatorId, long eventId, long initialDrawingId) {
    }
}
