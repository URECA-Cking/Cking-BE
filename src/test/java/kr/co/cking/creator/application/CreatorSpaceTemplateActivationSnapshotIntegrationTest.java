package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorSpaceTemplateRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL REPEATABLE READ에서, 트랜잭션의 첫 SELECT가 advisory lock 획득보다 먼저 실행되면
 * 그 시점에 consistent-read 스냅샷이 고정된다는 것을 직접 재현해서 검증한다.
 * {@code activate()}가 requireAdmin으로 lock을 잡기 전에 이미 한 번 SELECT를 실행하므로,
 * lock 안에서 일반 조회를 다시 해도 그 오래된 스냅샷을 그대로 쓸 수 있다 — 그래서
 * {@code findByActiveMarkerForActivation}(FOR UPDATE)이 필요하다.
 */
@SpringBootTest
class CreatorSpaceTemplateActivationSnapshotIntegrationTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorSpaceTemplateRepository templateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    private Long adminId;
    private Long otherTemplateId;

    @AfterEach
    void cleanUp() {
        if (adminId != null) {
            jdbcTemplate.update("DELETE FROM creator_space_template WHERE created_by = ?", adminId);
            jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", adminId);
        }
    }

    @Test
    void forUpdateReadSeesLatestCommittedActivationDespiteEarlierPinnedSnapshot() throws Exception {
        transactionTemplate = new TransactionTemplate(transactionManager);
        adminId = transactionTemplate.execute(status ->
                memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN)).getMemberId());
        otherTemplateId = transactionTemplate.execute(status ->
                templateRepository.save(newTemplate()).getTemplateId());

        CountDownLatch snapshotPinned = new CountDownLatch(1);
        CountDownLatch otherActivated = new CountDownLatch(1);
        AtomicReference<Boolean> plainReadSawActive = new AtomicReference<>();
        AtomicReference<Boolean> lockingReadSawActive = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            Future<?> reader = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                // activate()의 requireAdmin과 동일한 효과: 트랜잭션의 첫 SELECT로 스냅샷을 고정한다.
                memberRepository.findById(adminId);
                snapshotPinned.countDown();
                await(otherActivated);

                plainReadSawActive.set(templateRepository.findByActiveMarker(CreatorSpaceTemplate.ACTIVE_MARKER).isPresent());
                lockingReadSawActive.set(
                        templateRepository.findByActiveMarkerForActivation(CreatorSpaceTemplate.ACTIVE_MARKER).isPresent());
            }));

            snapshotPinned.await();
            // 별도 트랜잭션에서 다른 템플릿을 활성화하고 커밋한다 — reader 스레드의 스냅샷 고정 이후.
            transactionTemplate.executeWithoutResult(status -> {
                CreatorSpaceTemplate other = templateRepository.findById(otherTemplateId).orElseThrow();
                other.activate(adminId);
                templateRepository.saveAndFlush(other);
            });
            otherActivated.countDown();

            reader.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(plainReadSawActive.get())
                .as("일반 조회는 lock 획득보다 먼저 고정된 스냅샷을 그대로 써서 다른 트랜잭션의 커밋을 놓친다")
                .isFalse();
        assertThat(lockingReadSawActive.get())
                .as("FOR UPDATE 조회는 스냅샷과 무관하게 항상 최신 커밋 데이터를 읽는다")
                .isTrue();
    }

    private CreatorSpaceTemplate newTemplate() {
        return new CreatorSpaceTemplate(
                adminId, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}",
                true, true, true, true
        );
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
