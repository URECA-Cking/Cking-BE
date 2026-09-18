package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.presentation.EventCloseController;
import kr.co.cking.event.presentation.dto.EventCloseRequest;
import kr.co.cking.event.presentation.dto.EventCloseResponse;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.event.scheduler.EventLifecycleScheduler;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.snapshot.application.OfficialSnapshotService;

/** 시스템4 수동 마감 요청이 시스템2 마감 처리 완료까지 연결되는지 검증한다. */
@SpringBootTest(properties = {
        "cking.entry.stream-key=stream:ticket-deducted:manual-close-integration-test",
        "cking.entry.history-consumer-group=cg:ticket-history:manual-close-integration-test",
        "cking.event.lifecycle-interval-ms=86400000"
})
class ManualEventCloseServiceIntegrationTest {

    private static final String STREAM_KEY = "stream:ticket-deducted:manual-close-integration-test";
    private static final String CONSUMER_GROUP = "cg:ticket-history:manual-close-integration-test";
    private static final String CONSUMER = "manual-close-integration-test-consumer";

    @Autowired
    private ManualEventCloseService manualEventCloseService;

    @Autowired
    private EventCloseController eventCloseController;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventLifecycleScheduler eventLifecycleScheduler;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private EventClosingService eventClosingService;

    @MockitoSpyBean
    private EventCommandService eventCommandService;

    @MockitoBean
    private OfficialSnapshotService officialSnapshotService;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> creatorIds = new ArrayList<>();
    private final List<Long> eventIds = new ArrayList<>();

    /** Creator 본인 요청이 202 응답과 시스템2 서비스 호출로 이어져 CLOSING과 cutoff를 확정하는지 검증한다. */
    @Test
    void creatorCanCloseOwnOpenEventAndReuseSameCutoff() {
        Fixture owner = createCreatorFixture("수동 마감 크리에이터");
        Event event = persistOpenEvent(owner);
        redisTemplate.opsForValue().set(EntryRedisKeys.status(event.getEventId()), "OPEN");

        ResponseEntity<ApiResponse<EventCloseResponse>> first = eventCloseController.close(
                event.getEventId(),
                new EventCloseRequest(owner.memberId())
        );
        String firstCutoff = eventRepository.findById(event.getEventId()).orElseThrow().getCutoffStreamId();
        EventClosingService.ClosingResult second = manualEventCloseService.close(owner.memberId(), event.getEventId());
        Event persisted = eventRepository.findById(event.getEventId()).orElseThrow();

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().code()).isEqualTo("SUCCESS");
        assertThat(first.getBody().data()).isEqualTo(new EventCloseResponse(event.getEventId(), EventStatus.CLOSING));
        assertThat(second.status()).isEqualTo(EventStatus.CLOSING);
        assertThat(persisted.getStatus()).isEqualTo(EventStatus.CLOSING);
        assertThat(persisted.getCutoffStreamId()).isEqualTo(firstCutoff).isNotBlank();
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.status(event.getEventId()))).isEqualTo("CLOSED");
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.cutoff(event.getEventId()))).isEqualTo(firstCutoff);
        assertThat(redisTemplate.opsForStream().size(STREAM_KEY)).isEqualTo(1L);
        verify(eventClosingService, times(2)).startClosing(event.getEventId());
    }

    /** ADMIN은 자신이 소유하지 않은 OPEN Event도 시스템2 마감 서비스로 요청할 수 있는지 검증한다. */
    @Test
    void adminCanCloseAnyOpenEvent() {
        Fixture owner = createCreatorFixture("Event 소유 크리에이터");
        Member admin = createMember("관리자", MemberRole.ADMIN);
        Event event = persistOpenEvent(owner);
        redisTemplate.opsForValue().set(EntryRedisKeys.status(event.getEventId()), "OPEN");

        EventClosingService.ClosingResult result = manualEventCloseService.close(admin.getMemberId(), event.getEventId());

        assertThat(result.status()).isEqualTo(EventStatus.CLOSING);
        assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getCutoffStreamId()).isNotBlank();
        verify(eventClosingService).startClosing(event.getEventId());
    }

    /** 다른 Creator의 Event 마감 요청은 Redis나 시스템2 호출 없이 FORBIDDEN으로 거부되는지 검증한다. */
    @Test
    void anotherCreatorCannotCloseEvent() {
        Fixture owner = createCreatorFixture("Event 소유 크리에이터");
        Fixture anotherCreator = createCreatorFixture("다른 크리에이터");
        Event event = persistOpenEvent(owner);

        assertThatThrownBy(() -> manualEventCloseService.close(anotherCreator.memberId(), event.getEventId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        assertThat(redisTemplate.hasKey(EntryRedisKeys.cutoff(event.getEventId()))).isFalse();
    }

    /** cutoff까지 ACK된 뒤 스케줄러가 completeClosing을 호출하고 CLOSED·closedAt을 기록하는지 검증한다. */
    @Test
    void drainedEventIsCompletedAndRecordsClosedAt() {
        Fixture owner = createCreatorFixture("Drain 완료 크리에이터");
        Event event = persistOpenEvent(owner);
        redisTemplate.opsForValue().set(EntryRedisKeys.status(event.getEventId()), "OPEN");
        manualEventCloseService.close(owner.memberId(), event.getEventId());
        acknowledgeCutoff(event.getEventId());

        eventLifecycleScheduler.run();

        Event persisted = eventRepository.findById(event.getEventId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EventStatus.CLOSED);
        assertThat(persisted.getClosedAt()).isNotNull();
        verify(eventCommandService).completeClosing(event.getEventId());
    }

    /** 테스트가 만든 DB 행과 Redis 키만 역순으로 제거해 다른 통합 테스트에 영향을 주지 않는다. */
    @AfterEach
    void cleanUp() {
        eventIds.forEach(this::deleteEventRedisData);
        eventIds.forEach(eventRepository::deleteById);
        creatorIds.forEach(creatorRepository::deleteById);
        memberIds.forEach(memberRepository::deleteById);
    }

    /** Event 소유 권한을 가진 Creator와 연결 Member를 생성한다. */
    private Fixture createCreatorFixture(String name) {
        Member member = createMember(name, MemberRole.USER);
        Creator creator = creatorRepository.saveAndFlush(new Creator(member.getMemberId(), name));
        creatorIds.add(creator.getCreatorId());
        return new Fixture(member.getMemberId(), creator.getCreatorId());
    }

    /** 지정 역할의 Member를 저장하고 정리 대상에 등록한다. */
    private Member createMember(String name, MemberRole role) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, role));
        memberIds.add(member.getMemberId());
        return member;
    }

    /** 수동 마감을 시작할 수 있는 OPEN Event를 저장한다. */
    private Event persistOpenEvent(Fixture fixture) {
        Event event = eventRepository.saveAndFlush(Event.builder()
                .creatorId(fixture.creatorId())
                .requestId(UUID.randomUUID().toString())
                .title("수동 마감 연동 테스트")
                .startAt(Instant.now().minusSeconds(60))
                .endAt(Instant.now().plusSeconds(60))
                .winnerCount(1)
                .drawMethod(DrawMethod.WEIGHTED.name())
                .status(EventStatus.OPEN)
                .createdBy(fixture.memberId())
                .createdAt(Instant.now())
                .build());
        eventIds.add(event.getEventId());
        return event;
    }

    /** cutoff barrier 메시지를 Consumer Group이 읽고 ACK해 Drain 완료 조건을 만든다. */
    private void acknowledgeCutoff(Long eventId) {
        String cutoffStreamId = eventRepository.findById(eventId).orElseThrow().getCutoffStreamId();
        redisTemplate.opsForStream().read(
                Consumer.from(CONSUMER_GROUP, CONSUMER),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, CONSUMER_GROUP, RecordId.of(cutoffStreamId));
    }

    private void deleteEventRedisData(Long eventId) {
        eventRepository.findById(eventId)
                .map(Event::getCutoffStreamId)
                .ifPresent(cutoffStreamId -> redisTemplate.opsForStream()
                        .delete(STREAM_KEY, RecordId.of(cutoffStreamId)));
        redisTemplate.delete(EntryRedisKeys.status(eventId));
        redisTemplate.delete(EntryRedisKeys.cutoff(eventId));
    }

    /** Member와 Creator의 소유 관계를 함께 보관한다. */
    private record Fixture(Long memberId, Long creatorId) {
    }
}
