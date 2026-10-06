package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.auth.repository.AdminAccountRepository;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 실제 Spring Transaction Proxy와 DB 제약으로 관리자 초기 시딩 경쟁을 검증한다. */
@SpringBootTest
class AdminAccountInitializationConcurrencyIntegrationTest {

    @Autowired private AdminAccountInitializationService initializationService;
    @Autowired private AdminAccountRepository adminAccountRepository;
    @Autowired private MemberRepository memberRepository;

    /** 같은 로그인 ID를 동시에 초기화해도 두 호출이 모두 성공하고 Member와 계정이 하나씩만 남는지 검증한다. */
    @Test
    void concurrentInitializationCreatesOnlyOneAdminAccountAndMember() throws Exception {
        String loginId = "admin-" + UUID.randomUUID();
        String rawPassword = UUID.randomUUID().toString();
        long accountCountBefore = adminAccountRepository.count();
        long memberCountBefore = memberRepository.count();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> first = executor.submit(() -> initializeAfterSignal(ready, start, loginId, rawPassword));
            Future<Void> second = executor.submit(() -> initializeAfterSignal(ready, start, loginId, rawPassword));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Future<Void>> results = List.of(first, second);
            for (Future<Void> result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(adminAccountRepository.count()).isEqualTo(accountCountBefore + 1);
        assertThat(memberRepository.count()).isEqualTo(memberCountBefore + 1);
        assertThat(adminAccountRepository.findByLoginId(loginId)).isPresent();
    }

    /** 시작 신호를 받은 뒤 별도 스레드에서 관리자 계정 초기화를 실행한다. */
    private Void initializeAfterSignal(
            CountDownLatch ready,
            CountDownLatch start,
            String loginId,
            String rawPassword
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        initializationService.initialize(loginId, rawPassword);
        return null;
    }
}
