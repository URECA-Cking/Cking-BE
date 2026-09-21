package kr.co.cking.ticket.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
    private static final String NEXT_PERIOD_KEY = "2026-09-17";
    private static final String NEXT_PERIOD_KEY_GUARD_FORMAT = "20260917";
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

        service = new TicketEarnServiceImpl(redisTemplate, script, TEST_STREAM_KEY, new ObjectMapper());

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
        redisTemplate.delete(TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, NEXT_PERIOD_KEY_GUARD_FORMAT));
        redisTemplate.delete(TicketRedisKeys.earnGuard(USER_ID, OTHER_MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT));
        redisTemplate.delete(TicketRedisKeys.maintenanceLock(CREATOR_ID, USER_ID));
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

    // findExisting()만 부르는 테스트도 idem 키 정리를 위해 requestId를 등록해야 한다.
    private EarnLookupResult findExisting(EarnCommand command) {
        requestIds.add(command.requestId());
        return service.findExisting(command);
    }

    // PROCESSING record를 직접 구성하기 위한 현재 fingerprint 공식이다.
    private String fingerprint(EarnCommand command) throws NoSuchAlgorithmException {
        String payload = command.userId() + ":" + command.creatorId() + ":" + command.missionType()
                + ":" + command.missionId() + ":" + command.missionKey() + ":" + command.amount();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));

        return HexFormat.of().formatHex(hash);
    }

    // guardKeyField가 null이면 guardKey 없는 기존 PROCESSING 레코드를 만든다.
    private String seedStuckProcessing(EarnCommand command, String guardKey, String guardKeyField)
            throws NoSuchAlgorithmException {
        String fp = fingerprint(command);
        redisTemplate.opsForValue().set(TicketRedisKeys.balance(CREATOR_ID, USER_ID), "1");
        redisTemplate.opsForValue().set(guardKey, command.requestId() + ":" + fp);
        String json = guardKeyField != null
                ? "{\"fingerprint\":\"" + fp + "\",\"status\":\"PROCESSING\",\"guardKey\":\"" + guardKeyField + "\"}"
                : "{\"fingerprint\":\"" + fp + "\",\"status\":\"PROCESSING\"}";
        redisTemplate.opsForValue().set(TicketRedisKeys.idemMission(command.requestId().toString()), json);
        return fp;
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

    // 자정 이후 periodKey가 달라도 동일 requestId 재시도는 replay한다.
    @Test
    void periodKey만_다른_재시도는_자정_경계와_무관하게_ALREADY_PROCESSED를_반환한다() {
        UUID requestId = UUID.randomUUID();
        EarnResult first = earn(newCommand(requestId));
        assertThat(first.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        EarnCommand retryNextDay =
                new EarnCommand(requestId, USER_ID, CREATOR_ID, MISSION_TYPE, MISSION_ID, NEXT_PERIOD_KEY, MISSION_KEY, 1L);
        EarnResult retry = earn(retryNextDay);

        assertThat(retry.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
    }

    // periodKey가 달라도 missionKey나 amount처럼 실질적인 내용이 다르면 여전히
    // REQUEST_ID_CONFLICT여야 한다 - periodKey 제외가 다른 필드의 구분력까지
    // 없애면 안 된다.
    @Test
    void periodKey와_amount가_함께_다른_재시도는_REQUEST_ID_CONFLICT를_반환한다() {
        UUID requestId = UUID.randomUUID();
        EarnResult first = earn(newCommand(requestId));
        assertThat(first.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        EarnCommand conflicting =
                new EarnCommand(requestId, USER_ID, CREATOR_ID, MISSION_TYPE, MISSION_ID, NEXT_PERIOD_KEY, MISSION_KEY, 5L);
        EarnResult conflictResult = earn(conflicting);

        assertThat(conflictResult.code()).isEqualTo(EarnResultCode.REQUEST_ID_CONFLICT);
    }

    // 같은 미션이라도 periodKey(날짜)가 다르면 별도 Guard 키를 쓰므로, 새
    // requestId·새 periodKey는 일일 중복 보상 규칙에 따라 정상적으로 지급돼야 한다.
    @Test
    void 새_requestId와_새_periodKey면_정상적으로_EARN_ACCEPTED를_반환한다() {
        EarnResult day1 = earn(newCommand(UUID.randomUUID()));
        assertThat(day1.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        EarnCommand day2Command = new EarnCommand(
                UUID.randomUUID(), USER_ID, CREATOR_ID, MISSION_TYPE, MISSION_ID, NEXT_PERIOD_KEY, MISSION_KEY, 1L);
        EarnResult day2 = earn(day2Command);

        assertThat(day2.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("2");
    }

    @Test
    void idem과_Guard_TTL이_25시간으로_저장된다() {
        EarnResult result = earn(newCommand(UUID.randomUUID()));
        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        Long idemTtl = redisTemplate.getExpire(
                TicketRedisKeys.idemMission(requestIds.get(requestIds.size() - 1).toString()), TimeUnit.SECONDS);
        Long guardTtl = redisTemplate.getExpire(
                TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT), TimeUnit.SECONDS);

        assertThat(idemTtl).isBetween(89_990L, 90_000L);
        assertThat(guardTtl).isBetween(89_990L, 90_000L);
    }

    @Test
    void findExisting_기록이_없으면_NOT_FOUND를_반환한다() {
        EarnLookupResult result = findExisting(newCommand(UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(EarnLookupStatus.NOT_FOUND);
    }

    @Test
    void findExisting_성공_처리된_요청은_ALREADY_PROCESSED를_반환한다() {
        EarnCommand command = newCommand(UUID.randomUUID());
        EarnResult earnResult = earn(command);
        assertThat(earnResult.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        EarnLookupResult lookup = findExisting(command);

        assertThat(lookup.status()).isEqualTo(EarnLookupStatus.ALREADY_PROCESSED);
    }

    // 활성 상태 검증 전에 호출될 replay 조회도 자정 경계를 통과해야 한다.
    @Test
    void findExisting은_periodKey만_다른_재시도도_ALREADY_PROCESSED를_반환한다() {
        UUID requestId = UUID.randomUUID();
        EarnResult day1 = earn(newCommand(requestId));
        assertThat(day1.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        EarnCommand day2Lookup =
                new EarnCommand(requestId, USER_ID, CREATOR_ID, MISSION_TYPE, MISSION_ID, NEXT_PERIOD_KEY, MISSION_KEY, 1L);
        EarnLookupResult lookup = findExisting(day2Lookup);

        assertThat(lookup.status()).isEqualTo(EarnLookupStatus.ALREADY_PROCESSED);
    }

    @Test
    void findExisting_같은_requestId_다른_amount는_REQUEST_ID_CONFLICT를_반환한다() {
        UUID requestId = UUID.randomUUID();
        earn(newCommand(requestId));

        EarnCommand conflicting =
                new EarnCommand(requestId, USER_ID, CREATOR_ID, MISSION_TYPE, MISSION_ID, PERIOD_KEY, MISSION_KEY, 5L);
        EarnLookupResult lookup = findExisting(conflicting);

        assertThat(lookup.status()).isEqualTo(EarnLookupStatus.REQUEST_ID_CONFLICT);
    }

    // PROCESSING만 남은 요청은 신규 지급으로 진행하면 안 된다.
    @Test
    void idem이_PROCESSING_상태면_earn과_findExisting_모두_신규_지급으로_진행하지_않는다()
            throws NoSuchAlgorithmException {
        EarnCommand command = newCommand(UUID.randomUUID());
        requestIds.add(command.requestId());
        String fp = fingerprint(command);
        redisTemplate.opsForValue().set(
                TicketRedisKeys.idemMission(command.requestId().toString()),
                "{\"fingerprint\":\"" + fp + "\",\"status\":\"PROCESSING\"}"
        );

        EarnResult earnResult = service.earn(command);
        EarnLookupResult lookupResult = service.findExisting(command);

        assertThat(earnResult.code()).isEqualTo(EarnResultCode.EARN_STATUS_UNKNOWN);
        assertThat(lookupResult.status()).isEqualTo(EarnLookupStatus.UNAVAILABLE);
        assertThat(redisTemplate.hasKey(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isFalse();
    }

    @Test
    void findExisting은_PROCESSING이어도_Guard가_일치하면_ALREADY_PROCESSED를_반환한다()
            throws NoSuchAlgorithmException {
        EarnCommand command = newCommand(UUID.randomUUID());
        requestIds.add(command.requestId());
        String guardKey = TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT);
        seedStuckProcessing(command, guardKey, null);

        EarnLookupResult lookupResult = service.findExisting(command);

        assertThat(lookupResult.status()).isEqualTo(EarnLookupStatus.ALREADY_PROCESSED);
    }

    // 복구는 잔액을 다시 증가시키지 않고, self-heal 뒤에도 재현돼야 한다.
    @Test
    void earn의_Guard_폴백_복구는_잔액을_재증가하지_않고_idem을_COMPLETED로_self_heal한다()
            throws NoSuchAlgorithmException {
        EarnCommand command = newCommand(UUID.randomUUID());
        requestIds.add(command.requestId());
        String guardKey = TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT);
        seedStuckProcessing(command, guardKey, null);

        EarnResult recovered = service.earn(command);

        assertThat(recovered.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");

        // Guard가 사라져도(TTL 만료 흉내) self-heal된 idem만으로 계속 재현돼야 한다.
        redisTemplate.delete(guardKey);

        EarnResult retried = service.earn(command);
        EarnLookupResult lookupResult = service.findExisting(command);

        assertThat(retried.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(lookupResult.status()).isEqualTo(EarnLookupStatus.ALREADY_PROCESSED);
    }

    // 자정 이후에도 PROCESSING 예약 당시의 Guard를 사용한다.
    @Test
    void PROCESSING_Guard_폴백은_자정을_넘긴_재시도에서도_저장된_원래_Guard_키를_찾는다()
            throws NoSuchAlgorithmException {
        EarnCommand command = newCommand(UUID.randomUUID());
        requestIds.add(command.requestId());
        String originalGuardKey =
                TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT);
        seedStuckProcessing(command, originalGuardKey, originalGuardKey);

        EarnCommand retryNextDay = new EarnCommand(
                command.requestId(), USER_ID, CREATOR_ID, MISSION_TYPE, MISSION_ID, NEXT_PERIOD_KEY, MISSION_KEY, 1L);

        EarnLookupResult lookupResult = service.findExisting(retryNextDay);
        EarnResult earnResult = service.earn(retryNextDay);

        assertThat(lookupResult.status()).isEqualTo(EarnLookupStatus.ALREADY_PROCESSED);
        assertThat(earnResult.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
    }

    // guardKey 없는 기존 PROCESSING 레코드는 같은 날짜 재시도를 지원한다.
    @Test
    void guardKey_필드가_없는_legacy_PROCESSING_record도_같은_날짜_재시도는_ALREADY_PROCESSED로_복구한다()
            throws NoSuchAlgorithmException {
        EarnCommand command = newCommand(UUID.randomUUID());
        requestIds.add(command.requestId());
        String guardKey = TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT);
        seedStuckProcessing(command, guardKey, null);

        EarnResult earnResult = service.earn(command);
        EarnLookupResult lookupResult = service.findExisting(command);

        assertThat(earnResult.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(lookupResult.status()).isEqualTo(EarnLookupStatus.ALREADY_PROCESSED);
    }

    // 정상 earn() 경로가 guardKey를 기록하는지 검증한다.
    @Test
    void earn은_실제로_사용한_Guard_키를_idem에_기록한다() throws Exception {
        EarnCommand command = newCommand(UUID.randomUUID());
        EarnResult result = earn(command);
        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        String stored = redisTemplate.opsForValue().get(TicketRedisKeys.idemMission(command.requestId().toString()));
        Map<String, Object> idemRecord =
                new ObjectMapper().readValue(stored, new TypeReference<Map<String, Object>>() { });

        assertThat(idemRecord.get("guardKey")).isEqualTo(
                TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT));
    }

    @Test
    void idem이_PROCESSING이고_Guard도_다른_요청_값이면_여전히_신규_지급으로_진행하지_않는다()
            throws NoSuchAlgorithmException {
        EarnCommand command = newCommand(UUID.randomUUID());
        requestIds.add(command.requestId());
        String fp = fingerprint(command);
        redisTemplate.opsForValue().set(
                TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT),
                UUID.randomUUID() + ":" + fp
        );
        redisTemplate.opsForValue().set(
                TicketRedisKeys.idemMission(command.requestId().toString()),
                "{\"fingerprint\":\"" + fp + "\",\"status\":\"PROCESSING\"}"
        );

        EarnResult earnResult = service.earn(command);
        EarnLookupResult lookupResult = service.findExisting(command);

        assertThat(earnResult.code()).isEqualTo(EarnResultCode.EARN_STATUS_UNKNOWN);
        assertThat(lookupResult.status()).isEqualTo(EarnLookupStatus.UNAVAILABLE);
        assertThat(redisTemplate.hasKey(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isFalse();
    }

    // status 없는 이전 형식 record도 완료된 성공으로 호환한다.
    @Test
    void status_필드가_없는_legacy_idem_레코드도_ALREADY_PROCESSED를_반환한다()
            throws NoSuchAlgorithmException {
        EarnCommand command = newCommand(UUID.randomUUID());
        requestIds.add(command.requestId());
        // 실제 legacy 레코드는 옛 공식(periodKey 포함)으로 계산된 fingerprint를
        // 저장하고 있었다 - 지금 공식(periodKey 제외)으로 계산하면 절대 일치하지
        // 않는다는 걸 증명하기 위해 일부러 옛 공식으로 만든 값을 쓴다.
        String legacyFp = legacyFingerprint(command);
        redisTemplate.opsForValue().set(
                TicketRedisKeys.idemMission(command.requestId().toString()),
                "{\"fingerprint\":\"" + legacyFp + "\",\"result\":[\"EARN_ACCEPTED\",\"legacy-stream-id\",\"3\"]}"
        );

        EarnResult earnResult = service.earn(command);
        EarnLookupResult lookupResult = service.findExisting(command);

        assertThat(earnResult.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(lookupResult.status()).isEqualTo(EarnLookupStatus.ALREADY_PROCESSED);
        assertThat(redisTemplate.hasKey(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isFalse();
    }

    // 이전 형식 record는 fingerprint와 무관하게 기존 결과를 재현한다.
    @Test
    void legacy_idem은_fingerprint가_완전히_달라도_ALREADY_PROCESSED로_재현된다()
            throws NoSuchAlgorithmException {
        EarnCommand command = newCommand(UUID.randomUUID());
        requestIds.add(command.requestId());
        redisTemplate.opsForValue().set(
                TicketRedisKeys.idemMission(command.requestId().toString()),
                "{\"fingerprint\":\"completely-different-fingerprint\","
                        + "\"result\":[\"EARN_ACCEPTED\",\"legacy-stream-id\",\"3\"]}"
        );

        EarnResult earnResult = service.earn(command);

        assertThat(earnResult.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(redisTemplate.hasKey(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isFalse();
    }

    // issue #172: 수동 보정(resyncRedisToDb) 중에는 신규 PROCESSING 예약조차 만들지
    // 않아야 한다. BALANCE_MAINTENANCE(HTTP 503)는 EarnResultCode에 추가될 새 코드다 -
    // TicketCompensationService 쪽 PR이 그 enum 값과 HTTP 매핑을 추가하기 전까지는
    // 이 테스트가 컴파일되지 않는다(합의된 순서).
    @Test
    void 수동_보정_락이_걸려있으면_BALANCE_MAINTENANCE를_반환하고_잔액과_idem을_건드리지_않는다() {
        EarnCommand command = newCommand(UUID.randomUUID());
        requestIds.add(command.requestId());
        redisTemplate.opsForValue().set(TicketRedisKeys.maintenanceLock(CREATOR_ID, USER_ID), "locked");

        EarnResult result = service.earn(command);

        assertThat(result.code()).isEqualTo(EarnResultCode.BALANCE_MAINTENANCE);
        assertThat(redisTemplate.hasKey(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isFalse();
        assertThat(redisTemplate.hasKey(TicketRedisKeys.idemMission(command.requestId().toString()))).isFalse();
        assertThat(redisTemplate.hasKey(
                TicketRedisKeys.earnGuard(USER_ID, MISSION_TYPE, CREATOR_ID, PERIOD_KEY_GUARD_FORMAT)
        )).isFalse();
    }

    // 이미 완료된 요청의 replay는 보정 락과 무관하게 기존 결과를 그대로 재현해야 한다 -
    // idem COMPLETED 분기가 락 확인보다 먼저 실행되므로 replay는 락에 막히지 않는다.
    @Test
    void 수동_보정_락이_걸려있어도_이미_완료된_요청은_ALREADY_PROCESSED로_재현된다() {
        EarnCommand command = newCommand(UUID.randomUUID());
        EarnResult first = earn(command);
        assertThat(first.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        redisTemplate.opsForValue().set(TicketRedisKeys.maintenanceLock(CREATOR_ID, USER_ID), "locked");
        EarnResult retry = service.earn(command);

        assertThat(retry.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(CREATOR_ID, USER_ID))).isEqualTo("1");
    }

    // 이전 형식의 periodKey 포함 fingerprint 공식이다.
    private String legacyFingerprint(EarnCommand command) throws NoSuchAlgorithmException {
        String payload = command.userId() + ":" + command.creatorId() + ":" + command.missionType()
                + ":" + command.missionId() + ":" + command.periodKey() + ":" + command.missionKey()
                + ":" + command.amount();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));

        return HexFormat.of().formatHex(hash);
    }
}
