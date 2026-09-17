package kr.co.cking.ticket.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 로컬 Redis에 붙어서 Lua 스크립트까지 검증한다(EventCacheTest와 동일하게
 * Spring 컨텍스트 없이 직접 연결 — 이 리포의 @SpringBootTest는 로컬 MySQL/Flyway
 * 상태에 따라 실패할 수 있어 그 경로를 타지 않는다). 이 테스트가 만든 키만
 * 지우도록 전용 stream 키와 고정 테스트 식별자를 매 테스트 전후로 정리한다.
 */
class TicketEarnServiceImplTest {

    private static final String TEST_STREAM_KEY = "stream:ticket-earned:test";
    private static final Long CREATOR_ID = 900001L;
    private static final Long USER_ID = 1L;
    private static final String MISSION_TYPE = "ATTENDANCE";
    private static final Long MISSION_ID = 10L;
    private static final String PERIOD_KEY = "2026-09-16";
    private static final String PERIOD_KEY_GUARD_FORMAT = "20260916";
    private static final String MISSION_KEY = "attendance:creator:2026-09-16";
    // Balance는 creatorId+userId 기준이라, 같은 Balance에 다른 미션으로 먼저
    // 적립해둘 때(가드가 겹치지 않도록)만 이 미션 타입을 쓴다.
    private static final String OTHER_MISSION_TYPE = "LIKE";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private TicketEarnServiceImpl service;
    // earn()으로 사용한 requestId를 모아뒀다가 teardown에서 idem 키까지 지운다.
    private final List<UUID> requestIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/ticket-earn.lua"));
        script.setResultType(List.class);

        service = new TicketEarnServiceImpl(redisTemplate, script, TEST_STREAM_KEY);

        cleanUpKeys();
    }

    @AfterEach
    void tearDown() {
        cleanUpKeys();
        connectionFactory.destroy();
    }

    private void cleanUpKeys() {
        redisTemplate.delete(TicketRedisKeys.balance(CREATOR_ID, USER_ID));
        redisTemplate.delete(TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT));
        redisTemplate.delete(TicketRedisKeys.earnGuard(USER_ID, OTHER_MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT));
        redisTemplate.delete(TEST_STREAM_KEY);
        requestIds.forEach(id -> redisTemplate.delete(TicketRedisKeys.idemMission(id.toString())));
        requestIds.clear();
    }

    private EarnCommand newCommand(UUID requestId) {
        return new EarnCommand(requestId, USER_ID, CREATOR_ID, MISSION_TYPE, MISSION_ID, PERIOD_KEY, MISSION_KEY, 1L);
    }

    // 모든 테스트는 service.earn()을 직접 부르지 않고 이 메서드를 거친다.
    private EarnResult earn(EarnCommand command) {
        requestIds.add(command.requestId());
        return service.earn(command);
    }

    @Test
    void 잔액을_늘리고_스트림에_발행한다() {
        EarnResult result = earn(newCommand(UUID.randomUUID()));

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
        assertThat(redisTemplate.opsForStream().size(TEST_STREAM_KEY)).isEqualTo(1L);
    }

    // FR-P1-017: 같은 requestId 재전송은 재적립 없이 기존 성공 결과를 재현해야 한다.
    @Test
    void 같은_요청을_재시도하면_ALREADY_PROCESSED를_반환하고_잔액이_중복증가하지_않는다() {
        EarnCommand command = newCommand(UUID.randomUUID());

        EarnResult first = earn(command);
        EarnResult retry = earn(command);

        assertThat(first.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(retry.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
    }

    @Test
    void 같은_requestId에_다른_amount면_REQUEST_ID_CONFLICT를_반환한다() {
        UUID requestId = UUID.randomUUID();
        EarnCommand first = newCommand(requestId);
        EarnCommand conflicting =
                new EarnCommand(requestId, USER_ID, CREATOR_ID, MISSION_TYPE, MISSION_ID, PERIOD_KEY, MISSION_KEY, 5L);

        EarnResult firstResult = earn(first);
        EarnResult conflictResult = earn(conflicting);

        assertThat(firstResult.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(conflictResult.code()).isEqualTo(EarnResultCode.REQUEST_ID_CONFLICT);
    }

    // FR-P2-006: 새 requestId라도 같은 가드 키(userId+missionType+creatorId+yyyyMMdd)면
    // SETNX에 막혀 중복 지급되지 않아야 한다(EARN API Business Key와는 다른 키, FR-P2-008).
    @Test
    void 새_requestId로_같은_미션을_다시_요청하면_DUPLICATE_MISSION을_반환한다() {
        earn(newCommand(UUID.randomUUID()));

        EarnResult duplicate = earn(newCommand(UUID.randomUUID()));

        assertThat(duplicate.code()).isEqualTo(EarnResultCode.DUPLICATE_MISSION);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
    }

    // XADD 실패 시 가드도 풀어야 한다 - 안 풀면 실제로는 적립되지 않았는데도 오늘
    // 하루 이 미션을 다시 받을 수 없게 된다. 원래 Balance 키가 없었던 경우이므로
    // "0"이 아니라 키 자체가 사라져야 한다(§2.4, entry-spend.lua와 동일 원칙).
    @Test
    void XADD가_실패하고_원래_Balance_키가_없었으면_삭제로_복구한다() {
        redisTemplate.opsForValue().set(TEST_STREAM_KEY, "not-a-stream");

        EarnResult result = earn(newCommand(UUID.randomUUID()));

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_PROCESSING_FAILED);
        assertThat(redisTemplate.hasKey(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isFalse();
        assertThat(redisTemplate.hasKey(
                TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT)
        )).isFalse();
    }

    // 원래 Balance 키가 이미 존재했던 경우(다른 미션으로 먼저 적립된 상태)라면
    // 삭제가 아니라 DECRBY로 원래 값까지만 되돌려야 한다.
    @Test
    void XADD가_실패하고_원래_Balance_키가_있었으면_DECRBY로_복구한다() {
        EarnCommand priorMission = new EarnCommand(
                UUID.randomUUID(), USER_ID, CREATOR_ID, OTHER_MISSION_TYPE, 30L, PERIOD_KEY, "like:creator:2026-09-16", 1L);
        EarnResult priorResult = earn(priorMission);
        assertThat(priorResult.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        redisTemplate.opsForValue().set(TEST_STREAM_KEY, "not-a-stream");

        EarnResult result = earn(newCommand(UUID.randomUUID()));

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_PROCESSING_FAILED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
    }

    // 실패 시 idem을 저장하지 않는다는 보상 로직의 의도를 고정한다 - 같은
    // requestId로 재시도하면 ALREADY_PROCESSED가 아니라 다시 처리를 시도해
    // EARN_ACCEPTED로 끝나야 한다.
    @Test
    void XADD가_실패한_요청을_같은_requestId로_재시도하면_EARN_ACCEPTED를_반환한다() {
        redisTemplate.opsForValue().set(TEST_STREAM_KEY, "not-a-stream");
        EarnCommand command = newCommand(UUID.randomUUID());

        EarnResult failed = earn(command);
        assertThat(failed.code()).isEqualTo(EarnResultCode.EARN_PROCESSING_FAILED);

        redisTemplate.delete(TEST_STREAM_KEY);
        EarnResult retried = earn(command);

        assertThat(retried.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
    }

    // SETNX 이후 INCRBY 자체가 타입 충돌로 실패해도 가드가 남으면 안 된다 - 안 풀면
    // 실제로는 적립되지 않았는데도 오늘 하루 이 미션을 다시 받을 수 없게 된다.
    @Test
    void INCRBY가_실패하면_가드를_풀고_같은_requestId로_재시도할_수_있다() {
        redisTemplate.opsForValue().set(TicketRedisKeys.balance(CREATOR_ID, USER_ID), "not-a-number");
        EarnCommand command = newCommand(UUID.randomUUID());

        EarnResult failed = earn(command);

        assertThat(failed.code()).isEqualTo(EarnResultCode.EARN_PROCESSING_FAILED);
        assertThat(redisTemplate.hasKey(
                TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT)
        )).isFalse();

        redisTemplate.delete(TicketRedisKeys.balance(CREATOR_ID, USER_ID));
        EarnResult retried = earn(command);

        assertThat(retried.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
    }

    // System 2(EARN) 책임: periodKey가 zero-padding된 yyyy-MM-dd가 아니면 Lua 호출
    // 전에 거부해야 한다("2026-9-16" 같은 값, 취합v1.5.4 §4.5). IllegalArgumentException으로
    // 던져야 호출측이 공통 VALIDATION_FAILED(400)로 변환할 수 있다.
    @Test
    void periodKey_형식이_올바르지_않으면_IllegalArgumentException을_던진다() {
        EarnCommand command = new EarnCommand(
                UUID.randomUUID(), USER_ID, CREATOR_ID, MISSION_TYPE, MISSION_ID, "2026-9-16", MISSION_KEY, 1L);

        assertThatThrownBy(() -> earn(command)).isInstanceOf(IllegalArgumentException.class);
    }

    // idem SET 자체의 실패를 강제로 재현할 수는 없다(SET은 키 타입과 무관하게 항상
    // 성공). 대신 성공 뒤 idem 키만 수동으로 지워 "idem 저장 실패 직후"와 동일한
    // 상태(가드는 있고 idem은 없음)를 만들어, 가드 값(requestId+fingerprint)이
    // 보조 멱등성 장치로 동작하는지 검증한다.
    @Test
    void idem_키가_없어도_같은_requestId_재시도는_가드로_ALREADY_PROCESSED를_복구한다() {
        EarnCommand command = newCommand(UUID.randomUUID());
        EarnResult first = earn(command);
        assertThat(first.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        redisTemplate.delete(TicketRedisKeys.idemMission(command.requestId().toString()));
        EarnResult retried = earn(command);

        assertThat(retried.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
    }

    @Test
    void idem_키가_없어도_같은_requestId_다른_amount면_가드로_REQUEST_ID_CONFLICT를_반환한다() {
        UUID requestId = UUID.randomUUID();
        EarnCommand first = newCommand(requestId);
        EarnResult firstResult = earn(first);
        assertThat(firstResult.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        redisTemplate.delete(TicketRedisKeys.idemMission(requestId.toString()));
        EarnCommand conflicting =
                new EarnCommand(requestId, USER_ID, CREATOR_ID, MISSION_TYPE, MISSION_ID, PERIOD_KEY, MISSION_KEY, 5L);
        EarnResult conflictResult = earn(conflicting);

        assertThat(conflictResult.code()).isEqualTo(EarnResultCode.REQUEST_ID_CONFLICT);
    }
}
