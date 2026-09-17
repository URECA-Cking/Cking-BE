package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;

/** 시스템4 수동 마감 요청이 시스템2 마감 오케스트레이션으로 위임되는지 검증한다. */
@SpringBootTest(properties = "cking.entry.stream-key=stream:ticket-deducted:manual-close-integration-test")
class ManualEventCloseServiceIntegrationTest {

    private static final String STREAM_KEY = "stream:ticket-deducted:manual-close-integration-test";

    @Autowired
    private ManualEventCloseService manualEventCloseService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long memberId;
    private Long creatorId;
    private Long eventId;

    /** 실제 Redis Barrier와 DB 상태 전이를 거친 뒤 중복 요청도 같은 마감 결과로 수렴하는지 검증한다. */
    @Test
    void 수동_마감은_시스템2에_위임되어_Gate와_cutoff를_확정하고_CLOSING으로_전이한다() {
        Event event = persistOpenEvent();
        redisTemplate.opsForValue().set(EntryRedisKeys.status(event.getEventId()), "OPEN");

        EventClosingService.ClosingResult first = manualEventCloseService.close(memberId, event.getEventId());
        EventClosingService.ClosingResult second = manualEventCloseService.close(memberId, event.getEventId());

        Event persisted = eventRepository.findById(event.getEventId()).orElseThrow();
        assertThat(first.status()).isEqualTo(EventStatus.CLOSING);
        assertThat(second).isEqualTo(first);
        assertThat(persisted.getStatus()).isEqualTo(EventStatus.CLOSING);
        assertThat(persisted.getCutoffStreamId()).isNotBlank();
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.status(event.getEventId()))).isEqualTo("CLOSED");
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.cutoff(event.getEventId())))
                .isEqualTo(persisted.getCutoffStreamId());
        assertThat(redisTemplate.opsForStream().size(STREAM_KEY)).isEqualTo(1L);
    }

    /** 테스트가 만든 DB 행과 Redis 키만 제거해 다른 통합 테스트에 영향을 주지 않는다. */
    @AfterEach
    void cleanUp() {
        if (eventId != null) {
            redisTemplate.delete(List.of(EntryRedisKeys.status(eventId), EntryRedisKeys.cutoff(eventId), STREAM_KEY));
            eventRepository.deleteById(eventId);
        }
        if (creatorId != null) {
            creatorRepository.deleteById(creatorId);
        }
        if (memberId != null) {
            memberRepository.deleteById(memberId);
        }
    }

    /** 수동 마감 권한과 OPEN 상태를 모두 갖춘 Creator 소유 Event를 저장한다. */
    private Event persistOpenEvent() {
        Member member = memberRepository.saveAndFlush(new Member("수동 마감 크리에이터", null, null, MemberRole.USER));
        memberId = member.getMemberId();
        Creator creator = creatorRepository.saveAndFlush(new Creator(memberId, member.getName()));
        creatorId = creator.getCreatorId();
        Event event = eventRepository.saveAndFlush(Event.builder()
                .creatorId(creatorId)
                .requestId(UUID.randomUUID().toString())
                .title("수동 마감 연동 테스트")
                .startAt(Instant.now().minusSeconds(60))
                .endAt(Instant.now().plusSeconds(60))
                .winnerCount(1)
                .drawMethod(DrawMethod.WEIGHTED.name())
                .status(EventStatus.OPEN)
                .createdBy(memberId)
                .createdAt(Instant.now())
                .build());
        eventId = event.getEventId();
        return event;
    }
}
