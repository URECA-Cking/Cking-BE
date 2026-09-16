package kr.co.cking.ticket.application;

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

/**
 * 실제 로컬 Redis에 붙어서 Lua 스크립트까지 검증한다(EventCacheTest와 동일하게
 * Spring 컨텍스트 없이 직접 연결 — 이 리포의 @SpringBootTest는 로컬 MySQL/Flyway
 * 상태에 따라 실패할 수 있어 그 경로를 타지 않는다). 이 테스트가 만든 키만
 * 지우도록 전용 prefix(stream)와 고정 Business Key를 매 테스트 전후로 정리한다.
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

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private TicketEarnServiceImpl service;

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
        redisTemplate.delete(TEST_STREAM_KEY);
    }

    private EarnCommand newCommand(UUID requestId) {
        return new EarnCommand(requestId, USER_ID, CREATOR_ID, MISSION_TYPE, MISSION_ID, PERIOD_KEY, MISSION_KEY, 1L);
    }

    @Test
    void 잔액을_늘리고_스트림에_발행한다() {
        EarnResult result = service.earn(newCommand(UUID.randomUUID()));

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
        assertThat(redisTemplate.opsForStream().size(TEST_STREAM_KEY)).isEqualTo(1L);
    }

    // FR-P1-017: 같은 requestId 재전송은 재적립 없이 기존 성공 결과를 재현해야 한다.
    // (예전 버전은 가드가 없어서 이 케이스가 실제로 잔액을 중복 증가시켰다 - 이슈 #30에서 고침)
    @Test
    void 같은_요청을_재시도하면_ALREADY_PROCESSED를_반환하고_잔액이_중복증가하지_않는다() {
        EarnCommand command = newCommand(UUID.randomUUID());

        EarnResult first = service.earn(command);
        EarnResult retry = service.earn(command);

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

        EarnResult firstResult = service.earn(first);
        EarnResult conflictResult = service.earn(conflicting);

        assertThat(firstResult.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(conflictResult.code()).isEqualTo(EarnResultCode.REQUEST_ID_CONFLICT);
    }

    // FR-P2-006: 새 requestId라도 같은 Business Key(userId+creatorId+missionId+periodKey)면
    // 가드(SETNX)에 막혀 중복 지급되지 않아야 한다.
    @Test
    void 새_requestId로_같은_미션을_다시_요청하면_DUPLICATE_MISSION을_반환한다() {
        service.earn(newCommand(UUID.randomUUID()));

        EarnResult duplicate = service.earn(newCommand(UUID.randomUUID()));

        assertThat(duplicate.code()).isEqualTo(EarnResultCode.DUPLICATE_MISSION);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
    }

    // XADD 실패 시 Balance뿐 아니라 가드도 풀어야 한다 - 안 풀면 실제로는 적립되지
    // 않았는데도 오늘 하루 이 미션을 영원히 다시 받을 수 없게 된다.
    @Test
    void XADD가_실패하면_잔액과_가드를_모두_보상한다() {
        redisTemplate.opsForValue().set(TEST_STREAM_KEY, "not-a-stream");

        EarnResult result = service.earn(newCommand(UUID.randomUUID()));

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_PROCESSING_FAILED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("0");
        assertThat(redisTemplate.hasKey(
                TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT)
        )).isFalse();
    }
}
