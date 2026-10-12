package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorActivityName;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 승인 중 최초 활동명 할당과 다른 트랜잭션의 활동명 선택이 겹쳐도,
 * 이름 namespace lock을 기다린 뒤 재조회하여 접미사 후보로 수렴하는지 검증한다.
 */
@SpringBootTest
class CreatorActivityNameAllocationConcurrencyIntegrationTest {

    @Autowired
    private CreatorActivityNameService activityNameService;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long createdMemberId;

    @AfterEach
    void cleanUp() {
        if (createdMemberId != null) {
            creatorRepository.findByMemberId(createdMemberId).ifPresent(creatorRepository::delete);
            memberRepository.deleteById(createdMemberId);
        }
    }

    @Test
    void 동시_최초_활동명_할당은_승인을_실패시키지_않고_접미사_후보로_재시도한다() throws Exception {
        String requested = "alloc" + Long.toString(System.nanoTime(), 36);
        createdMemberId = memberRepository.saveAndFlush(new Member("첫번째", null, null, MemberRole.USER)).getMemberId();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch firstCreatorSaved = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                CreatorActivityName allocated = activityNameService.initialAvailableName(requested);
                creatorRepository.saveAndFlush(new Creator(createdMemberId, "첫번째", allocated));
                firstCreatorSaved.countDown();
                await(commitFirst);
            }));

            assertThat(firstCreatorSaved.await(5, TimeUnit.SECONDS)).isTrue();
            Future<CreatorActivityName> second = executor.submit(() -> activityNameService.initialAvailableName(requested));

            commitFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS).value()).isEqualTo(requested + "-2");
        } finally {
            commitFirst.countDown();
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("동시성 테스트 대기 시간이 초과되었습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
