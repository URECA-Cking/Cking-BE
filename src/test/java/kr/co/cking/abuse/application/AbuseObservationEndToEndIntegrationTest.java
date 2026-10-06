package kr.co.cking.abuse.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.co.cking.abuse.application.context.MissionBusinessKeyFactory;
import kr.co.cking.abuse.application.model.AbuseDetectionSearchCondition;
import kr.co.cking.abuse.application.port.AbuseDetectionRepository;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseCompositeRule;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.infrastructure.redis.AbuseRedisKeys;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.EventEntryService;
import kr.co.cking.event.application.EventQueryService;
import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.application.dto.EntryCommand;
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;
import kr.co.cking.event.application.service.EntrySpendService;
import kr.co.cking.event.domain.EntryErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.CommonMissionRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.application.CommonMissionCompletionService;
import kr.co.cking.mission.application.CreatorSpaceShareMissionCompletionService;
import kr.co.cking.mission.application.MissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.domain.CommonMissionType;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.application.CommonTicketEarnService;
import kr.co.cking.ticket.application.TicketEarnService;
import kr.co.cking.ticket.application.TicketOnceEarnService;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.domain.CouponType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Mission/Entry 업무 결과부터 Redis Feature, Rule, Cooldown, MySQL Detection까지 연결한다. */
@SpringBootTest(properties = {
        "cking.scheduling.enabled=false",
        "cking.abuse.enabled=true",
        "cking.abuse.mission-request-burst.window=PT1M",
        "cking.abuse.mission-request-burst.threshold=2",
        "cking.abuse.duplicate-mission-burst.window=PT1M",
        "cking.abuse.duplicate-mission-burst.threshold=2",
        "cking.abuse.entry-request-burst.window=PT1M",
        "cking.abuse.entry-request-burst.threshold=2",
        "cking.abuse.insufficient-balance-burst.window=PT1M",
        "cking.abuse.insufficient-balance-burst.threshold=2",
        "cking.abuse.insufficient-balance-burst.consecutive-threshold=2",
        "cking.abuse.request-id-rotation.window=PT1M",
        "cking.abuse.request-id-rotation.distinct-threshold=2",
        "cking.abuse.rapid-earn-and-spend.max-delay=PT1M",
        "cking.abuse.rapid-earn-and-spend.window=PT2M",
        "cking.abuse.rapid-earn-and-spend.threshold=2",
        "cking.abuse.failure-burst.window=PT1M",
        "cking.abuse.failure-burst.threshold=10",
        "cking.abuse.failure-burst.consecutive-threshold=10",
        "cking.abuse.failure-burst.distinct-type-threshold=10"
})
class AbuseObservationEndToEndIntegrationTest {

    @Autowired private MissionCompletionService creatorMissionService;
    @Autowired private CreatorSpaceShareMissionCompletionService shareMissionService;
    @Autowired private CommonMissionCompletionService commonMissionService;
    @Autowired private EventEntryService entryService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private MissionRepository missionRepository;
    @Autowired private CommonMissionRepository commonMissionRepository;
    @Autowired private AbuseDetectionRepository detectionRepository;
    @Autowired private MissionBusinessKeyFactory businessKeyFactory;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    // Ticket EARN/SPEND 자체의 Redis·Stream 계약은 기존 통합 테스트가 검증한다.
    // 여기서는 그 확정 결과만 통제하고 Abuse 경계부터 실제 Redis·MySQL을 사용한다.
    @MockitoBean private TicketEarnService ticketEarnService;
    @MockitoBean private TicketOnceEarnService ticketOnceEarnService;
    @MockitoBean private CommonTicketEarnService commonTicketEarnService;
    @MockitoBean private EntrySpendService entrySpendService;
    @MockitoBean private EventQueryService eventQueryService;

    private Member member;
    private Creator creator;
    private Mission likeMission;
    private Mission shareMission;
    private Long commonMissionId;
    private final Long eventId = 987654321L;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(new Member("Abuse E2E 회원", null, null, MemberRole.USER));
        Member owner = memberRepository.saveAndFlush(new Member("Abuse E2E Creator", null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "Abuse E2E Creator"));
        likeMission = missionRepository.saveAndFlush(new Mission(creator.getCreatorId(), MissionType.LIKE, 1, null, null));
        shareMission = missionRepository.saveAndFlush(new Mission(creator.getCreatorId(), MissionType.SHARE, 1, null, null));
        commonMissionId = commonMissionRepository.findByTypeIn(List.of(CommonMissionType.ATTENDANCE))
                .getFirst().getMissionId();
        when(eventQueryService.getCachedEvent(eventId)).thenReturn(new CachedEvent(
                eventId, creator.getCreatorId(), "Abuse E2E Event", "설명",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"),
                10, "WEIGHTED", EventStatus.OPEN));
    }

    @AfterEach
    void cleanUp() {
        if (member == null) {
            return;
        }
        Long userId = member.getMemberId();
        jdbcTemplate.update("delete from abuse_detection where member_id = ?", userId);
        redisTemplate.delete(redisKeysFor(userId));
        missionRepository.deleteAllById(List.of(likeMission.getMissionId(), shareMission.getMissionId()));
        creatorRepository.deleteById(creator.getCreatorId());
        memberRepository.deleteById(userId);
        memberRepository.deleteById(creator.getMemberId());
    }

    @Test
    void LIKE_완료와_replay는_실제_Feature와_Detection에서_구분된다() {
        when(ticketEarnService.findExisting(any())).thenReturn(
                new EarnLookupResult(EarnLookupStatus.NOT_FOUND),
                new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED),
                new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));
        UUID first = UUID.randomUUID();

        assertThat(creatorMissionService.complete(creator.getCreatorId(), likeMission.getMissionId(),
                command(first)).code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(creatorMissionService.complete(creator.getCreatorId(), likeMission.getMissionId(),
                command(first)).code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(detections()).isEmpty();
        assertThat(creatorMissionService.complete(creator.getCreatorId(), likeMission.getMissionId(),
                command(UUID.randomUUID())).code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        var detection = only(AbuseType.MISSION_REQUEST_BURST);
        assertThat(detection.evidence().features().get(AbuseMetric.MISSION_REQUEST_COUNT)).isEqualTo(2L);
        assertThat(detection.evidence().matchedRules()).contains(AbuseCompositeRule.RULE_02);
        assertThat(detection.evidence().supportingEvidence().get(AbuseCompositeRule.RULE_02))
                .singleElement().satisfies(support -> {
                    assertThat(support.window().windowMs()).isEqualTo(60_000L);
                    assertThat(support.scope().missionId()).isEqualTo(likeMission.getMissionId());
                });
        assertThat(redisTemplate.opsForZSet().zCard(AbuseRedisKeys.missionRequest(member.getMemberId())))
                .isEqualTo(2L);
        creatorMissionService.complete(creator.getCreatorId(), likeMission.getMissionId(), command(UUID.randomUUID()));
        assertThat(detections()).hasSize(1); // 같은 USER scope의 Cooldown은 새 row를 만들지 않는다.
    }

    @Test
    void 중복_Mission_실패와_requestId_rotation은_별도_scope의_복합_근거로_저장된다() {
        when(ticketEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
        when(ticketEarnService.earn(any()))
                .thenReturn(new EarnResult(EarnResultCode.DUPLICATE_MISSION));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> creatorMissionService.complete(
                    creator.getCreatorId(), likeMission.getMissionId(), command(UUID.randomUUID())))
                    .isInstanceOf(BusinessException.class);
        }

        var duplicate = only(AbuseType.DUPLICATE_MISSION_BURST);
        assertThat(duplicate.evidence().scope().missionId()).isEqualTo(likeMission.getMissionId());
        assertThat(duplicate.evidence().features().get(AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT))
                .isEqualTo(2L);
        assertThat(duplicate.evidence().matchedRules()).contains(AbuseCompositeRule.RULE_01);
        assertThat(duplicate.evidence().supportingEvidence().get(AbuseCompositeRule.RULE_01))
                .singleElement().satisfies(support -> {
                    assertThat(support.window().windowMs()).isEqualTo(60_000L);
                    assertThat(support.scope().missionId()).isEqualTo(likeMission.getMissionId());
                    assertThat(support.features().get(AbuseMetric.DISTINCT_REQUEST_ID_COUNT)).isEqualTo(2L);
                });
    }

    @Test
    void Ticket_시스템_실패는_원래_예외를_유지하고_Feature를_추가하지_않는다() {
        when(ticketEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
        when(ticketEarnService.earn(any()))
                .thenReturn(new EarnResult(EarnResultCode.EARN_PROCESSING_FAILED));

        assertThatThrownBy(() -> creatorMissionService.complete(
                creator.getCreatorId(), likeMission.getMissionId(), command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(kr.co.cking.mission.domain.MissionErrorCode.EARN_PROCESSING_FAILED);

        assertThat(redisTemplate.hasKey(AbuseRedisKeys.missionRequest(member.getMemberId()))).isFalse();
        assertThat(detections()).isEmpty();
    }

    @Test
    void SHARE와_공용_ATTENDANCE도_Mission_Observation을_합산한다() {
        when(ticketOnceEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
        when(ticketOnceEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));
        when(commonTicketEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
        when(commonTicketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        assertThat(shareMissionService.complete(creator.getCreatorId(), command(UUID.randomUUID())).code())
                .isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(commonMissionService.complete(commonMissionId, command(UUID.randomUUID())).code())
                .isEqualTo(EarnResultCode.EARN_ACCEPTED);

        assertThat(only(AbuseType.MISSION_REQUEST_BURST).evidence().features()
                .get(AbuseMetric.MISSION_REQUEST_COUNT)).isEqualTo(2L);
        assertThat(redisTemplate.opsForZSet().zCard(AbuseRedisKeys.missionRequest(member.getMemberId())))
                .isEqualTo(2L);
    }

    @Test
    void Entry_부족_잔액_반복은_응모_업무_오류를_유지하며_범위별_Detection을_저장한다() {
        when(entrySpendService.spend(any(), any(), any(), any(), anyInt(), any())).thenReturn(
                EntrySpendResult.ofBalance(EntrySpendResultCode.INSUFFICIENT_BALANCE, 0L),
                EntrySpendResult.ofBalance(EntrySpendResultCode.INSUFFICIENT_BALANCE, 0L),
                EntrySpendResult.ofSuccess(EntrySpendResultCode.DUPLICATE_REPLAY, null, null));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> entryService.apply(eventId, entry(UUID.randomUUID(), CouponType.COMMON)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getErrorCode())
                    .isEqualTo(EntryErrorCode.INSUFFICIENT_BALANCE);
        }
        assertThat(entryService.apply(eventId, entry(UUID.randomUUID(), CouponType.COMMON)).code().name())
                .isEqualTo("DUPLICATE_REPLAY");

        assertThat(only(AbuseType.ENTRY_REQUEST_BURST).evidence().scope().eventId()).isEqualTo(eventId);
        assertThat(only(AbuseType.ENTRY_REQUEST_BURST).evidence().matchedRules())
                .contains(AbuseCompositeRule.RULE_03);
        var insufficient = only(AbuseType.INSUFFICIENT_BALANCE_BURST);
        assertThat(insufficient.evidence().scope().balanceScope()).isEqualTo(BalanceScope.common());
        assertThat(insufficient.evidence().features().get(AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT))
                .isEqualTo(2L);
        assertThat(redisTemplate.opsForZSet().zCard(AbuseRedisKeys.entryRequest(member.getMemberId(), eventId)))
                .isEqualTo(2L);
    }

    @Test
    void Creator_미션_EARN과_응모_SPEND가_같은_잔액_범위에서_빠르게_반복되면_탐지된다() {
        when(ticketOnceEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
        when(ticketOnceEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));
        when(ticketEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));
        when(entrySpendService.spend(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(EntrySpendResult.ofSuccess(EntrySpendResultCode.SUCCESS, "1-0", 0L));

        assertThat(shareMissionService.complete(creator.getCreatorId(), command(UUID.randomUUID())).code())
                .isEqualTo(EarnResultCode.EARN_ACCEPTED);
        entryService.apply(eventId, entry(UUID.randomUUID(), CouponType.CREATOR));
        assertThat(creatorMissionService.complete(creator.getCreatorId(), likeMission.getMissionId(),
                command(UUID.randomUUID())).code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        entryService.apply(eventId, entry(UUID.randomUUID(), CouponType.CREATOR));

        var rapid = only(AbuseType.RAPID_EARN_AND_SPEND);
        assertThat(rapid.evidence().scope().balanceScope())
                .isEqualTo(BalanceScope.creator(creator.getCreatorId()));
        assertThat(rapid.evidence().features().get(AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT)).isEqualTo(2L);
        assertThat(rapid.evidence().matchedRules()).contains(AbuseCompositeRule.RULE_04);
    }

    private MissionCompleteCommand command(UUID requestId) {
        return new MissionCompleteCommand(member.getMemberId(), requestId);
    }

    private EntryCommand entry(UUID requestId, CouponType couponType) {
        return new EntryCommand(member.getMemberId(), requestId, 1, couponType);
    }

    private List<kr.co.cking.abuse.domain.AbuseDetection> detections() {
        return detectionRepository.search(new AbuseDetectionSearchCondition(
                member.getMemberId(), null, null, null, null), PageRequest.of(0, 20)).getContent();
    }

    private kr.co.cking.abuse.domain.AbuseDetection only(AbuseType type) {
        return detections().stream().filter(detection -> detection.abuseType() == type)
                .findFirst().orElseThrow(() -> new AssertionError("Detection 누락: " + type));
    }

    private Set<String> redisKeysFor(Long userId) {
        Set<String> keys = new HashSet<>();
        BalanceScope creatorScope = BalanceScope.creator(creator.getCreatorId());
        BalanceScope commonScope = BalanceScope.common();
        keys.add(AbuseRedisKeys.missionRequest(userId));
        keys.add(AbuseRedisKeys.entryRequest(userId, eventId));
        keys.add(AbuseRedisKeys.failure(userId));
        keys.add(AbuseRedisKeys.failureType(userId));
        keys.add(AbuseRedisKeys.failureSequence(userId));
        for (BalanceScope scope : List.of(creatorScope, commonScope)) {
            keys.add(AbuseRedisKeys.insufficientBalance(userId, scope));
            keys.add(AbuseRedisKeys.insufficientBalanceSequence(userId, scope));
            keys.add(AbuseRedisKeys.lastEarn(userId, scope));
            keys.add(AbuseRedisKeys.rapidEarnSpend(userId, scope));
            String scopeHash = AbuseScopeHash.fromCanonicalValue(
                    "USER_BALANCE_SCOPE:" + userId + ":" + scope.canonicalValue());
            keys.add(AbuseRedisKeys.cooldown(AbuseType.INSUFFICIENT_BALANCE_BURST, scopeHash));
            keys.add(AbuseRedisKeys.cooldown(AbuseType.RAPID_EARN_AND_SPEND, scopeHash));
        }
        String likeKey = businessKeyFactory.forCreator(MissionType.LIKE, userId, creator.getCreatorId(),
                likeMission.getMissionId(), Instant.now()).value();
        String shareKey = businessKeyFactory.forCreator(MissionType.SHARE, userId, creator.getCreatorId(),
                shareMission.getMissionId(), Instant.now()).value();
        String commonKey = businessKeyFactory.forCommon(CommonMissionType.ATTENDANCE, userId,
                commonMissionId, Instant.now()).value();
        for (String businessKey : List.of(likeKey, shareKey, commonKey)) {
            keys.add(AbuseRedisKeys.duplicateMission(businessKey));
            keys.add(AbuseRedisKeys.requestIdRotation(businessKey));
            keys.add(AbuseRedisKeys.cooldown(AbuseType.DUPLICATE_MISSION_BURST,
                    AbuseScopeHash.fromCanonicalValue("BUSINESS_KEY:" + businessKey)));
        }
        String userHash = AbuseScopeHash.fromCanonicalValue("USER:" + userId);
        keys.add(AbuseRedisKeys.cooldown(AbuseType.MISSION_REQUEST_BURST, userHash));
        keys.add(AbuseRedisKeys.cooldown(AbuseType.FAILURE_BURST, userHash));
        keys.add(AbuseRedisKeys.cooldown(AbuseType.ENTRY_REQUEST_BURST,
                AbuseScopeHash.fromCanonicalValue("USER_EVENT:" + userId + ":" + eventId)));
        return keys;
    }
}
