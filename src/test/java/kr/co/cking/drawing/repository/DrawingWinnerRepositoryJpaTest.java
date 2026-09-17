package kr.co.cking.drawing.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingSnapshotContract;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.domain.WinnerManagement;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DrawingWinnerRepositoryJpaTest {

    @Autowired
    private DrawingRepository drawingRepository;

    @Autowired
    private WinnerRepository winnerRepository;

    @Autowired
    private WinnerManagementRepository winnerManagementRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void INITIAL_Drawing_전체필드를_저장하고_Event와_drawNo로_조회한다() {
        Fixture fixture = fixture();
        Drawing saved = drawingRepository.saveAndFlush(initialDrawing(fixture));
        entityManager.clear();

        Drawing found = drawingRepository.findByEventIdAndDrawNo(fixture.eventId(), 0).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getEventId()).isEqualTo(fixture.eventId());
        assertThat(found.getDrawNo()).isZero();
        assertThat(found.getDrawType()).isEqualTo(DrawingType.INITIAL);
        assertThat(found.getOriginalDrawingId()).isNull();
        assertThat(found.getRedrawRequestId()).isNull();
        assertThat(found.getSnapshotId()).isEqualTo(fixture.snapshotId());
        assertThat(found.getSeedId()).isEqualTo(fixture.seedId());
        assertThat(found.getDrawMethod()).isEqualTo("WEIGHTED");
        assertThat(found.getAlgorithmVersion()).isEqualTo("WEIGHTED_V1");
        assertThat(found.getWinnerCount()).isEqualTo(2);
        assertThat(found.getStatus()).isEqualTo(DrawingStatus.READY);
        assertThat(found.getVisibility()).isEqualTo(DrawingVisibility.PRIVATE);
        assertThat(found.getInputPayload()).isNull();
        assertThat(found.getOutputPayload()).isNull();
        assertThat(found.getInputHash()).isNull();
        assertThat(found.getResultHash()).isNull();
        assertThat(found.getRequestedBy()).isEqualTo(fixture.requestedBy());
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getFirstStartedAt()).isNull();
        assertThat(found.getCompletedAt()).isNull();
        assertThat(found.getPublishedAt()).isNull();
        assertThat(found.getAttemptCount()).isZero();
        assertThat(found.getVersion()).isZero();
        assertThat(drawingRepository.existsByEventIdAndDrawNo(fixture.eventId(), 0)).isTrue();
    }

    @Test
    void 동일_Event의_INITIAL_Drawing은_하나만_저장된다() {
        Fixture fixture = fixture();
        drawingRepository.saveAndFlush(initialDrawing(fixture));
        long secondSeedId = insertSeed();

        Drawing duplicate = Drawing.createInitial(
                snapshotContract(fixture),
                secondSeedId,
                fixture.requestedBy()
        );

        assertThatThrownBy(() -> drawingRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 동일_Seed는_둘_이상의_Drawing에_연결할_수_없다() {
        Fixture firstFixture = fixture();
        Fixture secondFixture = fixture();
        drawingRepository.saveAndFlush(initialDrawing(firstFixture));

        Drawing duplicate = Drawing.createInitial(
                snapshotContract(secondFixture),
                firstFixture.seedId(),
                secondFixture.requestedBy()
        );

        assertThatThrownBy(() -> drawingRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 동일_RedrawRequest는_둘_이상의_Drawing에_연결할_수_없다() {
        Fixture fixture = fixture();
        Drawing original = drawingRepository.saveAndFlush(initialDrawing(fixture));
        long redrawRequestId = insertRedrawRequest(fixture, original.getId());
        Drawing first = redrawDrawing(fixture, 1, insertSeed(), original.getId(), redrawRequestId);
        drawingRepository.saveAndFlush(first);
        Drawing duplicate = redrawDrawing(fixture, 2, insertSeed(), original.getId(), redrawRequestId);

        assertThatThrownBy(() -> drawingRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void Drawing의_Event와_Snapshot의_Event가_다르면_저장할_수_없다() {
        Fixture eventFixture = fixture();
        Fixture snapshotFixture = fixture();
        Drawing mismatch = Drawing.createInitial(
                snapshotContract(snapshotFixture),
                eventFixture.seedId(),
                eventFixture.requestedBy()
        );
        ReflectionTestUtils.setField(mismatch, "eventId", eventFixture.eventId());

        assertThatThrownBy(() -> drawingRepository.saveAndFlush(mismatch))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "drawMethod, UNIFORM",
            "algorithmVersion, WEIGHTED_V2"
    })
    void Drawing의_확정_입력이_Snapshot과_다르면_저장할_수_없다(
            String field,
            String mismatchedValue
    ) {
        Fixture fixture = fixture();
        Drawing mismatch = Drawing.createInitial(
                snapshotContract(fixture),
                fixture.seedId(),
                fixture.requestedBy()
        );
        ReflectionTestUtils.setField(mismatch, field, mismatchedValue);

        assertThatThrownBy(() -> drawingRepository.saveAndFlush(mismatch))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void Winner를_rankInDrawing_ASC로_조회하고_Management를_조회한다() {
        Fixture fixture = fixture();
        Drawing drawing = drawingRepository.saveAndFlush(initialDrawing(fixture));
        long firstMemberId = insertMember("후보1", "USER");
        long secondMemberId = insertMember("후보2", "USER");
        Winner second = winnerRepository.save(Winner.create(
                fixture.eventId(), drawing.getId(), secondMemberId, 2, 7L));
        Winner first = winnerRepository.save(Winner.create(
                fixture.eventId(), drawing.getId(), firstMemberId, 1, 3L));
        winnerRepository.flush();
        WinnerManagement management = winnerManagementRepository.saveAndFlush(
                WinnerManagement.selected(first.getId()));
        entityManager.clear();

        List<Winner> winners = winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(drawing.getId());

        assertThat(winners).extracting(Winner::getRankInDrawing).containsExactly(1, 2);
        assertThat(winners).extracting(Winner::getId).containsExactly(first.getId(), second.getId());
        assertThat(winners.get(0).getCreatedAt()).isNotNull();
        assertThat(winnerRepository.existsByEventIdAndMemberId(fixture.eventId(), firstMemberId)).isTrue();

        WinnerManagement found = winnerManagementRepository.findByWinnerId(first.getId()).orElseThrow();
        assertThat(found.getId()).isEqualTo(management.getId());
        assertThat(found.getStatus()).isEqualTo(WinnerManagementStatus.SELECTED);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void 동일_Drawing에서_Winner_순위는_중복될_수_없다() {
        Fixture fixture = fixture();
        Drawing drawing = drawingRepository.saveAndFlush(initialDrawing(fixture));
        long firstMemberId = insertMember("후보1", "USER");
        long secondMemberId = insertMember("후보2", "USER");
        winnerRepository.saveAndFlush(Winner.create(
                fixture.eventId(), drawing.getId(), firstMemberId, 1, 3L));

        Winner duplicate = Winner.create(
                fixture.eventId(), drawing.getId(), secondMemberId, 1, 5L);

        assertThatThrownBy(() -> winnerRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 동일_Event에서_같은_Member는_중복_당첨될_수_없다() {
        Fixture fixture = fixture();
        Drawing drawing = drawingRepository.saveAndFlush(initialDrawing(fixture));
        long memberId = insertMember("후보", "USER");
        winnerRepository.saveAndFlush(Winner.create(
                fixture.eventId(), drawing.getId(), memberId, 1, 3L));

        Winner duplicate = Winner.create(
                fixture.eventId(), drawing.getId(), memberId, 2, 5L);

        assertThatThrownBy(() -> winnerRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void Winner의_Event와_Drawing의_Event가_다르면_저장할_수_없다() {
        Fixture drawingFixture = fixture();
        Fixture winnerFixture = fixture();
        Drawing drawing = drawingRepository.saveAndFlush(initialDrawing(drawingFixture));
        long memberId = insertMember("후보", "USER");
        Winner mismatch = Winner.create(
                winnerFixture.eventId(), drawing.getId(), memberId, 1, 3L);

        assertThatThrownBy(() -> winnerRepository.saveAndFlush(mismatch))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void Winner에는_하나의_WinnerManagement만_연결할_수_있다() {
        Fixture fixture = fixture();
        Drawing drawing = drawingRepository.saveAndFlush(initialDrawing(fixture));
        long memberId = insertMember("후보", "USER");
        Winner winner = winnerRepository.saveAndFlush(Winner.create(
                fixture.eventId(), drawing.getId(), memberId, 1, 3L));
        winnerManagementRepository.saveAndFlush(WinnerManagement.selected(winner.getId()));

        WinnerManagement duplicate = WinnerManagement.selected(winner.getId());

        assertThatThrownBy(() -> winnerManagementRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Drawing initialDrawing(Fixture fixture) {
        return Drawing.createInitial(
                snapshotContract(fixture),
                fixture.seedId(),
                fixture.requestedBy()
        );
    }

    private DrawingSnapshotContract snapshotContract(Fixture fixture) {
        return DrawingSnapshotContract.from(new VerifiedSnapshot(
                fixture.snapshotId(),
                fixture.eventId(),
                0,
                0L,
                2,
                "WEIGHTED",
                "WEIGHTED_V1",
                "0".repeat(64),
                List.of()
        ));
    }

    private Drawing redrawDrawing(
            Fixture fixture,
            int drawNo,
            long seedId,
            long originalDrawingId,
            long redrawRequestId
    ) {
        Drawing drawing = Drawing.createInitial(
                snapshotContract(fixture),
                seedId,
                fixture.requestedBy()
        );
        ReflectionTestUtils.setField(drawing, "drawNo", drawNo);
        ReflectionTestUtils.setField(drawing, "drawType", DrawingType.REDRAW);
        ReflectionTestUtils.setField(drawing, "originalDrawingId", originalDrawingId);
        ReflectionTestUtils.setField(drawing, "redrawRequestId", redrawRequestId);
        return drawing;
    }

    private Fixture fixture() {
        long requestedBy = insertMember("관리자", "ADMIN");
        long creatorId = insertCreator(requestedBy);
        long eventId = insertEvent(creatorId, requestedBy);
        long snapshotId = insertSnapshot(eventId);
        long seedId = insertSeed();
        return new Fixture(requestedBy, eventId, snapshotId, seedId);
    }

    private long insertMember(String name, String role) {
        entityManager.createNativeQuery("INSERT INTO member (name, role) VALUES (:name, :role)")
                .setParameter("name", name)
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
                            :creatorId, '테스트 이벤트', :startAt, :endAt, 2, 'WEIGHTED',
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
                            :eventId, 0, 0, 2, 'WEIGHTED', 'WEIGHTED_V1', :snapshotHash, 'UNVERIFIED'
                        )
                        """)
                .setParameter("eventId", eventId)
                .setParameter("snapshotHash", "a".repeat(64))
                .executeUpdate();
        return lastInsertId();
    }

    private long insertSeed() {
        entityManager.createNativeQuery("INSERT INTO draw_seed (seed_value) VALUES (:seedValue)")
                .setParameter("seedValue", new byte[]{1, 2, 3})
                .executeUpdate();
        return lastInsertId();
    }

    private long insertRedrawRequest(Fixture fixture, long originalDrawingId) {
        entityManager.createNativeQuery("""
                        INSERT INTO redraw_request (
                            event_id, original_drawing_id, vacancy_count, idempotency_key,
                            status, execution_status, requested_by, requested_at
                        ) VALUES (
                            :eventId, :originalDrawingId, 1, :idempotencyKey,
                            'APPROVED', 'PENDING', :requestedBy, :requestedAt
                        )
                        """)
                .setParameter("eventId", fixture.eventId())
                .setParameter("originalDrawingId", originalDrawingId)
                .setParameter("idempotencyKey", UUID.randomUUID().toString())
                .setParameter("requestedBy", fixture.requestedBy())
                .setParameter("requestedAt", Instant.parse("2026-09-16T00:00:00Z"))
                .executeUpdate();
        return lastInsertId();
    }

    private long lastInsertId() {
        Number id = (Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult();
        return id.longValue();
    }

    private record Fixture(Long requestedBy, Long eventId, Long snapshotId, Long seedId) {
    }
}
