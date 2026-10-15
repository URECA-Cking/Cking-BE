package kr.co.cking.ticket.application;

import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.dto.CommonOnceEarnCommand;
import kr.co.cking.ticket.domain.TicketOnceEarnRequestStatus;
import kr.co.cking.ticket.repository.TicketCommonOnceEarnRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 DB UNIQUE 제약으로 Redis 실행 전 동시 선점을 검증한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommonOnceEarnRequestClaimService.class,
        CommonOnceEarnRequestClaimServiceIntegrationTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommonOnceEarnRequestClaimServiceIntegrationTest {
    @Autowired private CommonOnceEarnRequestClaimService service;
    @Autowired private TicketCommonOnceEarnRequestRepository repository;
    @Autowired private MemberRepository memberRepository;

    /** 별도 트랜잭션의 서로 다른 requestId가 같은 회원·종류를 동시에 선점할 수 없다. */
    @Test
    void 동시_요청은_회원_종류_업무키를_하나만_선점한다() throws Exception {
        Member member = memberRepository.save(new Member("회원", null,
                "once-" + UUID.randomUUID() + "@example.com", MemberRole.USER));
        CommonOnceEarnCommand first = command(member.getMemberId(), UUID.randomUUID());
        CommonOnceEarnCommand second = command(member.getMemberId(), UUID.randomUUID());
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<TicketOnceEarnRequestClaim> left = pool.submit(() -> { start.await(); return service.claim(first); });
            Future<TicketOnceEarnRequestClaim> right = pool.submit(() -> { start.await(); return service.claim(second); });
            start.countDown();
            assertThat(List.of(left.get(), right.get()))
                    .containsExactlyInAnyOrder(TicketOnceEarnRequestClaim.PENDING, TicketOnceEarnRequestClaim.DUPLICATE);
            assertThat(repository.findByMemberIdAndRewardType(member.getMemberId(), "SIGNUP")).isPresent();
        } finally {
            pool.shutdownNow();
            repository.findByMemberIdAndRewardType(member.getMemberId(), "SIGNUP").ifPresent(repository::delete);
            memberRepository.deleteById(member.getMemberId());
        }
    }

    /** 같은 UUID는 승인 상태를 재현하고 다른 내용은 충돌한다. */
    @Test
    void 같은_requestId는_승인을_재현하고_다른_내용은_거절한다() {
        Member member = memberRepository.save(new Member("회원", null,
                "once-" + UUID.randomUUID() + "@example.com", MemberRole.USER));
        CommonOnceEarnCommand command = command(member.getMemberId(), UUID.randomUUID());
        try {
            assertThat(service.claim(command)).isEqualTo(TicketOnceEarnRequestClaim.PENDING);
            service.accept(command.requestId().toString());
            assertThat(service.find(command)).isEqualTo(TicketOnceEarnRequestClaim.ACCEPTED);
            assertThat(service.claim(new CommonOnceEarnCommand(command.requestId(), member.getMemberId(),
                    "SIGNUP", 2L, ""))).isEqualTo(TicketOnceEarnRequestClaim.REQUEST_ID_CONFLICT);
            assertThat(repository.findByRequestId(command.requestId().toString()).orElseThrow().getStatus())
                    .isEqualTo(TicketOnceEarnRequestStatus.ACCEPTED);
        } finally {
            repository.findByRequestId(command.requestId().toString()).ifPresent(repository::delete);
            memberRepository.deleteById(member.getMemberId());
        }
    }

    /** 공용 1회 요청의 기본 입력을 만든다. */
    private CommonOnceEarnCommand command(Long memberId, UUID requestId) {
        return new CommonOnceEarnCommand(requestId, memberId, "SIGNUP", 1L, "");
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        /** 테스트에서 승인 시각을 일정하게 유지한다. */
        @Bean Clock clock() { return Clock.systemUTC(); }
    }
}
