package kr.co.cking.abuse.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import kr.co.cking.abuse.application.port.AbuseDetectionRecorder;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseTestFixtures;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionResult;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 Redis Cooldown과 MySQL 저장을 조합한 Detection Recorder 동시성 검증이다. */
@SpringBootTest(properties = {
        "cking.abuse.enabled=true",
        "cking.abuse.mission-request-burst.window=PT10S",
        "cking.abuse.mission-request-burst.threshold=3",
        "cking.abuse.duplicate-mission-burst.window=PT20S",
        "cking.abuse.duplicate-mission-burst.threshold=2",
        "cking.abuse.entry-request-burst.window=PT30S",
        "cking.abuse.entry-request-burst.threshold=4",
        "cking.abuse.insufficient-balance-burst.window=PT40S",
        "cking.abuse.insufficient-balance-burst.threshold=2",
        "cking.abuse.insufficient-balance-burst.consecutive-threshold=2",
        "cking.abuse.request-id-rotation.window=PT50S",
        "cking.abuse.request-id-rotation.distinct-threshold=3",
        "cking.abuse.rapid-earn-and-spend.max-delay=PT2S",
        "cking.abuse.rapid-earn-and-spend.window=PT60S",
        "cking.abuse.rapid-earn-and-spend.threshold=2",
        "cking.abuse.failure-burst.window=PT70S",
        "cking.abuse.failure-burst.threshold=2",
        "cking.abuse.failure-burst.consecutive-threshold=2",
        "cking.abuse.failure-burst.distinct-type-threshold=2"
})
class DefaultAbuseDetectionRecorderIntegrationTest {

    @Autowired
    private AbuseDetectionRecorder recorder;

    @Autowired
    private AbuseObservationExecutor observationExecutor;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 설정 Bean이 기본 Recorder를 주입해 Observation 실행 경계를 조립한다. */
    @Test
    void 설정이_DefaultRecorder를_주입한_ObservationExecutor를_조립한다() {
        assertThat(observationExecutor).isNotNull();
        assertThat(recorder).isInstanceOf(DefaultAbuseDetectionRecorder.class);
    }

    /** 같은 Detection을 동시에 기록해도 하나의 Lease 소유자만 INSERT한다. */
    @Test
    void 동일_Detection_동시_저장에서_하나만_영속화한다() throws Exception {
        Member member = memberRepository.saveAndFlush(new Member("동시 Detection 저장 회원", null, null, MemberRole.USER));
        DetectionResult result = new DetectionResult(
                AbuseType.MISSION_REQUEST_BURST,
                AbuseScopeHash.fromCanonicalValue("USER:RECORDER-CONCURRENT:" + member.getMemberId()),
                Instant.parse("2026-10-06T00:00:00Z"),
                AbuseTestFixtures.userEvidence());
        int requestCount = 16;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(requestCount)) {
            var futures = IntStream.range(0, requestCount)
                    .mapToObj(index -> executor.submit(
                            () -> recordAfterStart(ready, start, member.getMemberId(), result)))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        Integer storedCount = jdbcTemplate.queryForObject(
                "select count(*) from abuse_detection where member_id = ?", Integer.class, member.getMemberId());
        assertThat(storedCount).isEqualTo(1);
    }

    /** 호출자 Transaction이 롤백돼도 Recorder의 독립 Transaction 저장은 유지된다. */
    @Test
    void 호출자_트랜잭션이_롤백돼도_Detection은_저장된다() {
        Member member = memberRepository.saveAndFlush(new Member("독립 Transaction 검증 회원", null, null, MemberRole.USER));
        DetectionResult result = new DetectionResult(
                AbuseType.MISSION_REQUEST_BURST,
                AbuseScopeHash.fromCanonicalValue("USER:RECORDER-REQUIRES-NEW:" + member.getMemberId()),
                Instant.parse("2026-10-06T00:00:00Z"),
                AbuseTestFixtures.userEvidence());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recorder.record(member.getMemberId(), result);
            status.setRollbackOnly();
        });

        Integer storedCount = jdbcTemplate.queryForObject(
                "select count(*) from abuse_detection where member_id = ?", Integer.class, member.getMemberId());
        assertThat(storedCount).isEqualTo(1);
    }

    /** 모든 요청이 같은 시작 신호 뒤 Recorder를 호출하도록 대기한다. */
    private void recordAfterStart(
            CountDownLatch ready,
            CountDownLatch start,
            Long memberId,
            DetectionResult result
    ) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("동시 Detection 저장 시작 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("동시 Detection 저장 대기 중 인터럽트됐습니다.", exception);
        }
        recorder.record(memberId, result);
    }
}
