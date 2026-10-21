package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.event.application.dto.EventCounts;
import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.repository.EventRepository;

/**
 * 진행 중 이벤트 수·상태별 개수 집계(이슈 #639)가 목록 필터({@code EventRepository#search})와
 * 같은 규칙으로 세는지 확인한다.
 */
@SpringBootTest
class EventCountsIntegrationTest {

    private static final long OWNER_MEMBER_ID = 90601L;
    private static final long CREATOR_ID = 90602L;
    private static final long OTHER_OWNER_MEMBER_ID = 90603L;
    private static final long OTHER_CREATOR_ID = 90604L;

    @Autowired
    private EventQueryService eventQueryService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.delete("cache:event:counts");
        cleanUp();
        insertCreator(OWNER_MEMBER_ID, CREATOR_ID);
        insertCreator(OTHER_OWNER_MEMBER_ID, OTHER_CREATOR_ID);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM event WHERE creator_id IN (?, ?)", CREATOR_ID, OTHER_CREATOR_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id IN (?, ?)", CREATOR_ID, OTHER_CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", OWNER_MEMBER_ID, OTHER_OWNER_MEMBER_ID);
    }

    @Test
    void 진행_중_수는_IN_PROGRESS_목록_totalElements와_같고_진행_중이_없으면_키가_없다() {
        insertEvent(CREATOR_ID, "OPEN", "2099-01-01 00:00:00", false);
        insertEvent(CREATOR_ID, "OPEN", "2099-01-01 00:00:00", false);
        insertEvent(CREATOR_ID, "OPEN", "2020-01-01 00:00:00", false); // endAt 지남 → 종료
        insertEvent(CREATOR_ID, "OPEN", "2099-01-01 00:00:00", true);  // 삭제 → 제외
        archive(insertEvent(CREATOR_ID, "PUBLISHED", "2020-01-01 00:00:00", false)); // 보관 → 제외
        archive(insertEvent(CREATOR_ID, "OPEN", "2099-01-01 00:00:00", false)); // 보관 → 제외
        insertEvent(CREATOR_ID, "SCHEDULED", "2099-01-01 00:00:00", false);
        insertEvent(CREATOR_ID, "CLOSED", "2020-01-01 00:00:00", false);
        insertEvent(CREATOR_ID, "DRAFT", "2099-01-01 00:00:00", false);
        insertEvent(OTHER_CREATOR_ID, "SCHEDULED", "2099-01-01 00:00:00", false);

        Map<Long, Long> counts = eventQueryService.countInProgressByCreatorIds(List.of(CREATOR_ID, OTHER_CREATOR_ID));

        assertThat(counts).containsEntry(CREATOR_ID, 2L).doesNotContainKey(OTHER_CREATOR_ID);
        assertThat(counts.get(CREATOR_ID)).isEqualTo(listTotal(CREATOR_ID, DisplayStatus.IN_PROGRESS));
        assertThat(eventQueryService.countInProgressByCreatorIds(List.of())).isEmpty();
    }

    @Test
    void 상태별_개수는_각_목록_totalElements와_같고_total은_세_값의_합이다() {
        insertEvent(CREATOR_ID, "SCHEDULED", "2099-01-01 00:00:00", false);
        insertEvent(CREATOR_ID, "OPEN", "2099-01-01 00:00:00", false);
        insertEvent(CREATOR_ID, "OPEN", "2020-01-01 00:00:00", false);
        insertEvent(CREATOR_ID, "CLOSING", "2020-01-01 00:00:00", false);
        insertEvent(CREATOR_ID, "PUBLISHED", "2020-01-01 00:00:00", false);
        insertEvent(CREATOR_ID, "SCHEDULED", "2099-01-01 00:00:00", true);
        archive(insertEvent(CREATOR_ID, "PUBLISHED", "2020-01-01 00:00:00", false)); // 보관 → 제외
        insertEvent(CREATOR_ID, "PENDING_APPROVAL", "2099-01-01 00:00:00", false);
        insertEvent(CREATOR_ID, "REJECTED", "2099-01-01 00:00:00", false);

        EventCounts counts = eventQueryService.getEventCounts();

        assertThat(counts.upcoming()).isEqualTo(listTotal(null, DisplayStatus.UPCOMING));
        assertThat(counts.inProgress()).isEqualTo(listTotal(null, DisplayStatus.IN_PROGRESS));
        assertThat(counts.closed()).isEqualTo(listTotal(null, DisplayStatus.CLOSED));
        assertThat(counts.total()).isEqualTo(counts.upcoming() + counts.inProgress() + counts.closed());
        assertThat(counts.total()).isEqualTo(listTotal(null, null));
    }

    @Test
    void 개수는_5초_캐시를_쓰고_캐시가_있으면_DB_변경이_바로_보이지_않는다() {
        EventCounts first = eventQueryService.getEventCounts();
        insertEvent(CREATOR_ID, "SCHEDULED", "2099-01-01 00:00:00", false);

        assertThat(eventQueryService.getEventCounts()).isEqualTo(first);
        assertThat(redisTemplate.getExpire("cache:event:counts")).isBetween(1L, 5L);
    }

    private long listTotal(Long creatorId, DisplayStatus status) {
        return eventRepository.search(creatorId, status, Instant.now(), PageRequest.of(0, 1)).getTotalElements();
    }

    private void insertCreator(long memberId, long creatorId) {
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", memberId, "집계 회원", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name, activity_name, activity_name_normalized, activity_name_configured) VALUES (?, ?, ?, (@creator_fixture_activity_name := CONCAT('c', SUBSTRING(MD5(UUID()), 1, 19))), @creator_fixture_activity_name, true)",
                creatorId, memberId, "집계 크리에이터");
    }

    private void archive(long eventId) {
        jdbcTemplate.update("UPDATE event SET archived_at = NOW(6), archived_by = ? WHERE event_id = ?", OWNER_MEMBER_ID, eventId);
    }

    private long insertEvent(long creatorId, String status, String endAt, boolean deleted) {
        jdbcTemplate.update("""
                INSERT INTO event (creator_id, title, start_at, end_at, winner_count, draw_method,
                                    status, request_id, created_by, deleted_at)
                VALUES (?, ?, '2019-01-01 00:00:00', ?, 1, 'WEIGHTED', ?, ?, ?, ?)
                """, creatorId, "집계 이벤트", endAt, status, UUID.randomUUID().toString(),
                creatorId == CREATOR_ID ? OWNER_MEMBER_ID : OTHER_OWNER_MEMBER_ID,
                deleted ? "2026-01-01 00:00:00" : null);
        return jdbcTemplate.queryForObject("SELECT MAX(event_id) FROM event WHERE creator_id = ?", Long.class, creatorId);
    }
}
