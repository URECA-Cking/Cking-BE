package kr.co.cking.winner.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.winner.domain.WinnerManagement;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import kr.co.cking.winner.domain.WinnerStatusHistory;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

/** 관리자 자격 박탈 명령의 동일 Winner 동시 실행을 실제 DB 잠금으로 검증한다. */
@SpringBootTest
class AdminWinnerDisqualifyConcurrencyIntegrationTest {

    private static final String REASON = "이벤트 참여 조건을 충족하지 않았습니다.";

    @Autowired
    private AdminWinnerDisqualifyService adminWinnerDisqualifyService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WinnerManagementRepository winnerManagementRepository;

    @Autowired
    private WinnerStatusHistoryRepository winnerStatusHistoryRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 같은 Winner에 대한 동시 자격 박탈 요청 중 하나만 전이·이력 저장에 성공한다. */
    @Test
    void 동시_자격_박탈은_하나만_DISQUALIFIED로_전이하고_이력도_한_건만_저장한다() throws Exception {
        Fixture fixture = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<String> first = executor.submit(() -> executeDisqualify(start, fixture));
            Future<String> second = executor.submit(() -> executeDisqualify(start, fixture));
            start.countDown();

            List<String> results = List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS)
            );

            assertThat(results).containsExactlyInAnyOrder("SUCCESS", "INVALID_STATE");
            assertThat(winnerManagementRepository.findByWinnerId(fixture.winnerId()).orElseThrow().getStatus())
                    .isEqualTo(WinnerManagementStatus.DISQUALIFIED);
            List<WinnerStatusHistory> histories = winnerStatusHistoryRepository
                    .findByWinnerManagementIdOrderByCreatedAtAscIdAsc(fixture.winnerManagementId());
            assertThat(histories).hasSize(1);
            assertThat(histories.getFirst().getReason()).isEqualTo(REASON);
            assertThat(histories.getFirst().getChangedBy()).isEqualTo(fixture.adminId());
            assertThat(histories.getFirst().getCreatedAt()).isNotNull();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            cleanup(fixture);
        }
    }

    /** 병렬 자격 박탈 결과를 성공 또는 도메인 오류 코드로 변환한다. */
    private String executeDisqualify(CountDownLatch start, Fixture fixture) throws InterruptedException {
        start.await();
        try {
            adminWinnerDisqualifyService.disqualify(fixture.winnerId(), fixture.adminId(), REASON);
            return "SUCCESS";
        } catch (BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }

    /** 실제 FK 제약을 만족하는 관리자·SELECTED Winner·운영 정보를 저장한다. */
    private Fixture fixture() {
        Member admin = memberRepository.saveAndFlush(new Member("동시 자격 박탈 관리자", null, null, MemberRole.ADMIN));
        Member winnerMember = memberRepository.saveAndFlush(new Member("동시 자격 박탈 당첨자", null, null, MemberRole.USER));
        long creatorId = insert("creator", "creator_id", Map.of(
                "member_id", admin.getMemberId(),
                "name", "동시성 테스트 크리에이터"
        ));
        long eventId = insert("event", "event_id", Map.of(
                "creator_id", creatorId,
                "title", "동시성 테스트 이벤트",
                "start_at", Instant.parse("2026-09-01T00:00:00Z"),
                "end_at", Instant.parse("2026-09-15T00:00:00Z"),
                "winner_count", 1,
                "draw_method", "WEIGHTED",
                "status", "CLOSED",
                "created_by", admin.getMemberId(),
                "request_id", UUID.randomUUID().toString()
        ));
        long snapshotId = insert("draw_snapshot", "id", Map.of(
                "event_id", eventId,
                "candidate_count", 0,
                "total_ticket_count", 0,
                "winner_count", 1,
                "draw_method", "WEIGHTED",
                "algorithm_version", "WEIGHTED_V1",
                "snapshot_hash", "a".repeat(64),
                "verification_status", "UNVERIFIED"
        ));
        long seedId = insert("draw_seed", "id", Map.of("seed_value", new byte[]{1, 2, 3}));
        long drawingId = insert("drawing", "id", Map.ofEntries(
                Map.entry("event_id", eventId),
                Map.entry("draw_no", 0),
                Map.entry("draw_type", "INITIAL"),
                Map.entry("snapshot_id", snapshotId),
                Map.entry("seed_id", seedId),
                Map.entry("draw_method", "WEIGHTED"),
                Map.entry("algorithm_version", "WEIGHTED_V1"),
                Map.entry("winner_count", 1),
                Map.entry("status", "COMPLETED"),
                Map.entry("visibility", "PUBLIC"),
                Map.entry("requested_by", admin.getMemberId()),
                Map.entry("attempt_count", 0),
                Map.entry("version", 0)
        ));
        long winnerId = insert("winner", "id", Map.of(
                "event_id", eventId,
                "drawing_id", drawingId,
                "member_id", winnerMember.getMemberId(),
                "rank_in_drawing", 1,
                "applied_ticket_count", 1
        ));
        WinnerManagement management = winnerManagementRepository.saveAndFlush(WinnerManagement.selected(winnerId));
        return new Fixture(
                admin.getMemberId(),
                winnerMember.getMemberId(),
                creatorId,
                eventId,
                snapshotId,
                seedId,
                drawingId,
                winnerId,
                management.getId()
        );
    }

    /** 테스트 fixture가 남긴 데이터를 외래 키 의존성의 역순으로 삭제한다. */
    private void cleanup(Fixture fixture) {
        jdbcTemplate.update("delete from winner_status_history where winner_management_id = ?", fixture.winnerManagementId);
        jdbcTemplate.update("delete from winner_management where id = ?", fixture.winnerManagementId);
        jdbcTemplate.update("delete from winner where id = ?", fixture.winnerId);
        jdbcTemplate.update("delete from drawing where id = ?", fixture.drawingId);
        jdbcTemplate.update("delete from draw_snapshot where id = ?", fixture.snapshotId);
        jdbcTemplate.update("delete from draw_seed where id = ?", fixture.seedId);
        jdbcTemplate.update("delete from event where event_id = ?", fixture.eventId);
        jdbcTemplate.update("delete from creator where creator_id = ?", fixture.creatorId);
        jdbcTemplate.update("delete from member where member_id in (?, ?)", fixture.adminId, fixture.winnerMemberId);
    }

    /** 지정한 테이블에 테스트 데이터를 저장하고 자동 생성된 기본 키를 반환한다. */
    private long insert(String tableName, String keyColumn, Map<String, Object> values) {
        return new SimpleJdbcInsert(jdbcTemplate)
                .withTableName(tableName)
                .usingColumns(values.keySet().toArray(String[]::new))
                .usingGeneratedKeyColumns(keyColumn)
                .executeAndReturnKey(values)
                .longValue();
    }

    /** 동시 자격 박탈 검증에 필요한 관리자·Winner·운영 정보 식별자를 묶는다. */
    @RequiredArgsConstructor
    private static class Fixture {

        private final Long adminId;
        private final Long winnerMemberId;
        private final Long creatorId;
        private final Long eventId;
        private final Long snapshotId;
        private final Long seedId;
        private final Long drawingId;
        private final Long winnerId;
        private final Long winnerManagementId;

        /** 자격 박탈을 요청할 관리자 Member 식별자를 반환한다. */
        private Long adminId() {
            return adminId;
        }

        /** 동시에 자격 박탈할 Winner 식별자를 반환한다. */
        private Long winnerId() {
            return winnerId;
        }

        /** 상태 이력 수를 검증할 WinnerManagement 식별자를 반환한다. */
        private Long winnerManagementId() {
            return winnerManagementId;
        }
    }
}
