package kr.co.cking.ticket.application;

import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.application.dto.CommonOnceEarnCommand;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL·Redis·공용 EARN Consumer의 1회 적립 경계를 검증한다. */
@SpringBootTest(properties = {
        "cking.ticket.common-earn-stream-key=stream:common-ticket-earned:once-test",
        "cking.ticket.common-earn-consumer-group=cg:common-ticket-earn:once-test",
        "cking.scheduling.enabled=false"
})
class CommonTicketOnceEarnIntegrationTest {
    @Autowired private CommonTicketOnceEarnService service;
    @Autowired private MemberRepository members;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TicketEarnRequestClaimService globalClaims;
    @Autowired private CommonOnceEarnRequestClaimService onceClaims;
    @Autowired private CommonEarnEventDispatcher dispatcher;

    /** Redis 응답이 사라진 실행권은 증거 없이 다시 Lua를 호출하지 않는다. */
    @Test
    void 승인_불명_실행권은_같은_requestId로도_재실행하지_않는다() {
        Member member = newMember();
        CommonOnceEarnCommand command = command(member.getMemberId(), UUID.randomUUID());
        try {
            globalClaims.claim(command);
            onceClaims.claim(command);
            assertThat(onceClaims.beginExecution(command.requestId().toString())).isTrue();
            assertThat(service.findExisting(command).status()).isEqualTo(EarnLookupStatus.UNAVAILABLE);
            assertThat(service.findExisting(command).persistedRequest()).isTrue();
            assertThat(service.earn(command).code()).isEqualTo(EarnResultCode.EARN_STATUS_UNKNOWN);
            assertThat(redis.opsForValue().get(CommonTicketRedisKeys.balance(member.getMemberId()))).isNull();
        } finally {
            clean(member.getMemberId());
        }
    }

    /** 전역 UUID 선점만 있고 보상 선점이 없으면 최초 조건 검증을 생략할 수 없다. */
    @Test
    void 보상_영속_선점이_없으면_기존_요청으로_간주하지_않는다() {
        Member member = newMember();
        CommonOnceEarnCommand command = command(member.getMemberId(), UUID.randomUUID());
        try {
            globalClaims.claim(command);
            assertThat(service.findExisting(command).persistedRequest()).isFalse();
            assertThat(service.findExisting(new CommonOnceEarnCommand(command.requestId(), member.getMemberId(),
                    "SIGNUP", 1L, "changed-answer")).status()).isEqualTo(EarnLookupStatus.REQUEST_ID_CONFLICT);
        } finally {
            jdbc.update("DELETE FROM ticket_earn_request WHERE request_id = ?", command.requestId().toString());
            clean(member.getMemberId());
        }
    }

    /** Redis 실행 전 멈춘 요청은 회수 시간 이후 같은 UUID로 한 번만 승인한다. */
    @Test
    void 오래된_EXECUTING은_Redis_흔적이_없으면_같은_requestId로_복구한다() throws Exception {
        Member member = newMember();
        CommonOnceEarnCommand command = command(member.getMemberId(), UUID.randomUUID());
        try {
            globalClaims.claim(command);
            onceClaims.claim(command);
            assertThat(onceClaims.beginExecution(command.requestId().toString())).isTrue();
            jdbc.update("UPDATE ticket_common_once_earn_request SET executing_at = "
                    + "UTC_TIMESTAMP(6) - INTERVAL 3 MINUTE WHERE request_id = ?", command.requestId().toString());

            assertThat(service.earn(command).code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
            assertThat(service.earn(command).code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
            awaitLedger(command.requestId().toString());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM common_ticket_ledger WHERE member_id = ?",
                    Integer.class, member.getMemberId())).isEqualTo(1);
            assertThat(redis.opsForValue().get(CommonTicketRedisKeys.balance(member.getMemberId())))
                    .isEqualTo("1");
        } finally {
            clean(member.getMemberId());
        }
    }

    /** 오래된 요청이어도 Redis에 불명 PROCESSING 흔적이 있으면 재실행하지 않는다. */
    @Test
    void 오래된_EXECUTING에_Redis_불명_흔적이_있으면_재실행하지_않는다() {
        Member member = newMember();
        CommonOnceEarnCommand command = command(member.getMemberId(), UUID.randomUUID());
        try {
            globalClaims.claim(command);
            onceClaims.claim(command);
            assertThat(onceClaims.beginExecution(command.requestId().toString())).isTrue();
            jdbc.update("UPDATE ticket_common_once_earn_request SET executing_at = "
                    + "UTC_TIMESTAMP(6) - INTERVAL 3 MINUTE WHERE request_id = ?", command.requestId().toString());
            redis.opsForValue().set(CommonTicketRedisKeys.idemOnce(command.requestId().toString()),
                    "{\"fingerprint\":\"" + command.computeFingerprint() + "\",\"status\":\"PROCESSING\"}");

            assertThat(service.earn(command).code()).isEqualTo(EarnResultCode.EARN_STATUS_UNKNOWN);
            assertThat(redis.opsForValue().get(CommonTicketRedisKeys.balance(member.getMemberId()))).isNull();
        } finally {
            clean(member.getMemberId());
        }
    }

    /** Redis Guard가 사라져도 영속 Business Key가 다른 UUID의 두 번째 적립을 막는다. */
    @Test
    void Redis_Guard_유실_뒤에도_다른_requestId로_재적립되지_않는다() throws Exception {
        Member member = newMember();
        CommonOnceEarnCommand first = command(member.getMemberId(), UUID.randomUUID());
        try {
            assertThat(service.earn(first).code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
            assertThat(service.earn(first).code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
            assertThat(service.earn(new CommonOnceEarnCommand(first.requestId(), member.getMemberId(),
                    "SIGNUP", 1L, "changed-answer")).code()).isEqualTo(EarnResultCode.REQUEST_ID_CONFLICT);
            awaitLedger(first.requestId().toString());
            dispatcher.apply(Map.of("sourceType", "ONCE", "requestId", first.requestId().toString(),
                    "userId", member.getMemberId().toString(), "rewardType", "SIGNUP",
                    "amount", "1", "fingerprint", first.computeFingerprint()));
            redis.delete(List.of(CommonTicketRedisKeys.onceGuard(member.getMemberId(), "SIGNUP"),
                    CommonTicketRedisKeys.idemOnce(first.requestId().toString())));
            assertThat(service.earn(command(member.getMemberId(), UUID.randomUUID())).code())
                    .isEqualTo(EarnResultCode.DUPLICATE_MISSION);
            assertThat(redis.opsForValue().get(CommonTicketRedisKeys.balance(member.getMemberId())))
                    .isEqualTo("1");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM common_ticket_ledger WHERE member_id = ?",
                    Integer.class, member.getMemberId())).isEqualTo(1);
        } finally {
            clean(member.getMemberId());
        }
    }

    /** 서로 다른 UUID의 동시 지급 요청 중 하나만 Redis와 Consumer에 도달한다. */
    @Test
    void 동시_요청에서도_회원_종류당_원장과_잔액은_한번만_반영된다() throws Exception {
        Member member = newMember();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();
        try {
            Future<EarnResultCode> left = pool.submit(() -> { start.await(); return service.earn(command(member.getMemberId(), leftId)).code(); });
            Future<EarnResultCode> right = pool.submit(() -> { start.await(); return service.earn(command(member.getMemberId(), rightId)).code(); });
            start.countDown();
            assertThat(List.of(left.get(), right.get()))
                    .containsExactlyInAnyOrder(EarnResultCode.EARN_ACCEPTED, EarnResultCode.DUPLICATE_MISSION);
            String acceptedId = service.findAcceptedRewardTypes(member.getMemberId()).contains("SIGNUP")
                    ? jdbc.queryForObject("SELECT request_id FROM ticket_common_once_earn_request "
                    + "WHERE member_id = ?", String.class, member.getMemberId()) : null;
            assertThat(acceptedId).isNotNull();
            awaitLedger(acceptedId);
            assertThat(jdbc.queryForObject("SELECT balance FROM user_common_ticket_balance WHERE member_id = ?",
                    Long.class, member.getMemberId())).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
            clean(member.getMemberId());
        }
    }

    /** 테스트마다 고유 회원을 저장한다. */
    private Member newMember() {
        return members.save(new Member("회원", null, "once-" + UUID.randomUUID() + "@example.com", MemberRole.USER));
    }

    /** 웰컴 판정과 독립적인 Ticket 입력을 만든다. */
    private CommonOnceEarnCommand command(Long memberId, UUID id) {
        return new CommonOnceEarnCommand(id, memberId, "SIGNUP", 1L, "");
    }

    /** 비동기 Consumer가 원장 한 줄을 기록할 때까지 기다린다. */
    private void awaitLedger(String requestId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM common_ticket_ledger WHERE request_id = ?",
                    Integer.class, requestId);
            if (count == 1) { return; }
            Thread.sleep(50);
        }
        throw new AssertionError("공용 ONCE EARN 원장 반영 시간이 초과됐습니다: " + requestId);
    }

    /** FK 역순으로 테스트 데이터를 정리한다. */
    private void clean(Long memberId) {
        redis.delete(List.of(CommonTicketRedisKeys.balance(memberId),
                CommonTicketRedisKeys.onceGuard(memberId, "SIGNUP")));
        List<String> ids = jdbc.queryForList("SELECT request_id FROM ticket_common_once_earn_request "
                + "WHERE member_id = ?", String.class, memberId);
        ids.forEach(id -> redis.delete(CommonTicketRedisKeys.idemOnce(id)));
        jdbc.update("DELETE FROM common_ticket_ledger WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM user_common_ticket_balance WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM ticket_common_once_earn_request WHERE member_id = ?", memberId);
        ids.forEach(id -> jdbc.update("DELETE FROM ticket_earn_request WHERE request_id = ?", id));
        members.deleteById(memberId);
    }
}
