package kr.co.cking.abuse.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.abuse.application.port.AbuseDetectionRepository;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseDetectionStatus;
import kr.co.cking.abuse.domain.AbuseReviewDecision;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseTestFixtures;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionResult;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL REPEATABLE READ에서도 관리자 검토 재요청의 최신 상태 확인을 검증한다. */
@SpringBootTest
class AdminAbuseDetectionReviewServiceIntegrationTest {

    @Autowired
    private AdminAbuseDetectionReviewService reviewService;

    @Autowired
    private MemberQueryService memberQueryService;

    @Autowired
    private AbuseDetectionRepository abuseDetectionRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 같은 판정의 동시 재요청은 기존 Read View가 있어도 모두 멱등 성공한다. */
    @Test
    void 같은_판정의_동시_검토_재요청은_모두_멱등_성공한다() throws Exception {
        Member detectedMember = saveMember("동시 동일 판정 탐지 대상", MemberRole.USER);
        Member firstAdmin = saveMember("동시 동일 판정 관리자 하나", MemberRole.ADMIN);
        Member secondAdmin = saveMember("동시 동일 판정 관리자 둘", MemberRole.ADMIN);
        AbuseDetection saved = abuseDetectionRepository.save(AbuseDetection.detected(
                detectedMember.getMemberId(),
                new DetectionResult(
                        AbuseType.FAILURE_BURST,
                        AbuseScopeHash.fromCanonicalValue("REVIEW-SAME:" + detectedMember.getMemberId()),
                        Instant.parse("2026-10-06T00:00:00Z"),
                        AbuseTestFixtures.userEvidence())));
        CountDownLatch readViewReady = new CountDownLatch(2);
        CountDownLatch reviewStart = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AbuseDetection> first = executor.submit(() -> reviewAfterReadView(
                    readViewReady, reviewStart, firstAdmin.getMemberId(), saved.detectionId()));
            Future<AbuseDetection> second = executor.submit(() -> reviewAfterReadView(
                    readViewReady, reviewStart, secondAdmin.getMemberId(), saved.detectionId()));

            assertThat(readViewReady.await(5, TimeUnit.SECONDS)).isTrue();
            reviewStart.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(AbuseDetectionStatus.CONFIRMED);
            assertThat(second.get(10, TimeUnit.SECONDS).status()).isEqualTo(AbuseDetectionStatus.CONFIRMED);
        }
    }

    /** 권한 검증 SELECT로 Read View를 만든 뒤 같은 Transaction에서 검토를 실행한다. */
    private AbuseDetection reviewAfterReadView(
            CountDownLatch readViewReady,
            CountDownLatch reviewStart,
            Long adminId,
            Long detectionId
    ) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            memberQueryService.validateAdmin(adminId);
            readViewReady.countDown();
            awaitReviewStart(reviewStart);
            return reviewService.review(adminId, detectionId, AbuseReviewDecision.CONFIRMED);
        });
    }

    /** 두 Transaction이 모두 Read View를 만든 후에만 검토 조건부 UPDATE를 시작하도록 대기한다. */
    private void awaitReviewStart(CountDownLatch reviewStart) {
        try {
            if (!reviewStart.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("동시 검토 시작 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("동시 검토 시작 대기 중 인터럽트됐습니다.", exception);
        }
    }

    /** 지정한 역할로 검토 대상 또는 관리자를 저장한다. */
    private Member saveMember(String name, MemberRole role) {
        return memberRepository.saveAndFlush(new Member(name, null, null, role));
    }
}
