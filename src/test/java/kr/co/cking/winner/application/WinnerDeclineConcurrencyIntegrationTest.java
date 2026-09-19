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

@SpringBootTest
class WinnerDeclineConcurrencyIntegrationTest {

    @Autowired
    private WinnerDeclineService winnerDeclineService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WinnerManagementRepository winnerManagementRepository;

    @Autowired
    private WinnerStatusHistoryRepository winnerStatusHistoryRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 동시에 같은 Winner 포기를 요청하면 하나만 성공하고 나머지는 잠금 해제 뒤 INVALID_STATE로 실패한다. */
    @Test
    void 동시_당첨_포기는_하나만_DECLINED로_전이하고_이력도_한_건만_저장한다() throws Exception {
        Fixture fixture = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<String> first = executor.submit(() -> executeDecline(start, fixture));
            Future<String> second = executor.submit(() -> executeDecline(start, fixture));
            start.countDown();

            List<String> results = List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS)
            );

            assertThat(results).containsExactlyInAnyOrder("SUCCESS", "INVALID_STATE");
            assertThat(winnerManagementRepository.findByWinnerId(fixture.winnerId()).orElseThrow().getStatus())
                    .isEqualTo(WinnerManagementStatus.DECLINED);
            List<WinnerStatusHistory> histories = winnerStatusHistoryRepository.findAll().stream()
                    .filter(history -> history.getWinnerManagementId().equals(fixture.winnerManagementId()))
                    .toList();
            assertThat(histories).hasSize(1);
            assertThat(histories.getFirst().getChangedBy()).isEqualTo(fixture.memberId());
            assertThat(histories.getFirst().getCreatedAt()).isNotNull();
        } finally {
            executor.shutdownNow();
        }
    }

    /** 병렬 요청 결과를 성공 또는 도메인 오류 코드로 변환한다. */
    private String executeDecline(CountDownLatch start, Fixture fixture) throws InterruptedException {
        start.await();
        try {
            winnerDeclineService.decline(fixture.winnerId(), fixture.memberId());
            return "SUCCESS";
        } catch (BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }

    /** 실제 FK 제약을 만족하는 SELECTED Winner와 WinnerManagement 테스트 데이터를 저장한다. */
    private Fixture fixture() {
        Member member = memberRepository.saveAndFlush(new Member("동시 포기 당첨자", null, null, MemberRole.USER));
        long creatorId = insert("creator", "creator_id", Map.of(
                "member_id", member.getMemberId(),
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
                "created_by", member.getMemberId(),
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
                Map.entry("requested_by", member.getMemberId()),
                Map.entry("attempt_count", 0),
                Map.entry("version", 0)
        ));
        long winnerId = insert("winner", "id", Map.of(
                "event_id", eventId,
                "drawing_id", drawingId,
                "member_id", member.getMemberId(),
                "rank_in_drawing", 1,
                "applied_ticket_count", 1
        ));
        WinnerManagement management = winnerManagementRepository.saveAndFlush(WinnerManagement.selected(winnerId));
        return new Fixture(member.getMemberId(), winnerId, management.getId());
    }

    /** 지정한 테이블에 데이터를 저장하고 자동 생성된 기본 키를 반환한다. */
    private long insert(String tableName, String keyColumn, Map<String, Object> values) {
        return new SimpleJdbcInsert(jdbcTemplate)
                .withTableName(tableName)
                .usingColumns(values.keySet().toArray(String[]::new))
                .usingGeneratedKeyColumns(keyColumn)
                .executeAndReturnKey(values)
                .longValue();
    }

    /** 동시성 검증에 필요한 호출자·Winner·운영 상태 식별자를 묶는다. */
    @RequiredArgsConstructor
    private static class Fixture {

        private final Long memberId;
        private final Long winnerId;
        private final Long winnerManagementId;

        /** 당첨 포기를 요청할 Member 식별자를 반환한다. */
        private Long memberId() {
            return memberId;
        }

        /** 동시에 포기할 Winner 식별자를 반환한다. */
        private Long winnerId() {
            return winnerId;
        }

        /** 상태 이력 수를 검증할 WinnerManagement 식별자를 반환한다. */
        private Long winnerManagementId() {
            return winnerManagementId;
        }
    }
}
