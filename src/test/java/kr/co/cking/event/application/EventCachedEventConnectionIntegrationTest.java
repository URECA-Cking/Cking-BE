package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import kr.co.cking.creator.application.CreatorQueryService;

/** 캐시가 빈 순간 트랜잭션 안·밖의 호출이 섞여 몰려도 연결 풀이 바닥나 함께 멈추지 않는다. */
@SpringBootTest(properties = "cking.scheduling.enabled=false")
class EventCachedEventConnectionIntegrationTest {

    private static final long OWNER_MEMBER_ID = 90401L;
    private static final long CREATOR_ID = 90402L;

    @Autowired
    private EventQueryService eventQueryService;

    @Autowired
    private EntryStatusQueryService entryStatusQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private CreatorQueryService creatorQueryService;

    @Value("${spring.datasource.hikari.maximum-pool-size}")
    private int poolSize;

    private long eventId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OWNER_MEMBER_ID, "캐시적재 크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name, activity_name, activity_name_normalized, activity_name_configured) VALUES (?, ?, ?, (@creator_fixture_activity_name := CONCAT('c', SUBSTRING(MD5(UUID()), 1, 19))), @creator_fixture_activity_name, true)",
                CREATOR_ID, OWNER_MEMBER_ID, "캐시적재 크리에이터");
        String requestId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO event (creator_id, title, start_at, end_at, winner_count, draw_method,
                                    status, request_id, created_by)
                VALUES (?, ?, '2026-09-01 00:00:00', '2099-01-01 00:00:00', 1, 'WEIGHTED', 'OPEN', ?, ?)
                """, CREATOR_ID, "캐시적재 이벤트", requestId, OWNER_MEMBER_ID);
        eventId = jdbcTemplate.queryForObject("SELECT event_id FROM event WHERE request_id = ?", Long.class, requestId);
        eventQueryService.invalidate(eventId);
    }

    @AfterEach
    void tearDown() {
        eventQueryService.invalidate(eventId);
        cleanUp();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM event WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", OWNER_MEMBER_ID);
    }

    @Test
    void 연결을_쥔_호출과_쥐지_않은_호출이_함께_몰려도_모두_끝난다() throws Exception {
        // 읽기가 겹치도록 소유 회원 조회를 늦춘다. 이 사이에 연결을 쥔 응모 현황 조회가 풀 크기보다 많이 들어온다.
        doAnswer(invocation -> {
            Thread.sleep(300);
            return invocation.callRealMethod();
        }).when(creatorQueryService).findOwnerMemberId(any());
        int callers = poolSize * 2;
        ExecutorService executor = Executors.newFixedThreadPool(callers * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                results.add(executor.submit(startAfter(start, () -> eventQueryService.getCachedEvent(eventId))));
                results.add(executor.submit(startAfter(start, () -> entryStatusQueryService.getStatus(eventId, null))));
            }
            start.countDown();

            for (Future<?> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> Callable<T> startAfter(CountDownLatch start, Callable<T> call) {
        return () -> {
            start.await();
            return call.call();
        };
    }
}
