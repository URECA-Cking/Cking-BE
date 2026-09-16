package kr.co.cking.event.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.dto.CreateEventCommand;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CreatorEventConcurrencyIntegrationTest {
    @Autowired private CreatorEventService service;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;

    /** 동일 멱등 키의 병렬 생성이 하나의 Event 식별자로 수렴하는지 검증한다. */
    @Test
    void concurrentSameRequestReturnsSameEvent() throws Exception {
        Member member = memberRepository.saveAndFlush(new Member("동시생성", null, null, MemberRole.USER));
        creatorRepository.saveAndFlush(new Creator(member.getMemberId(), member.getName()));
        CreateEventCommand command = new CreateEventCommand(member.getMemberId(), "550e8400-e29b-41d4-a716-446655440008",
                "동시 이벤트", null, LocalDateTime.now(ZoneOffset.UTC).plusDays(1), LocalDateTime.now(ZoneOffset.UTC).plusDays(2), 1, DrawMethod.WEIGHTED);
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Event> first = executor.submit(() -> { start.await(); return service.create(command); });
            Future<Event> second = executor.submit(() -> { start.await(); return service.create(command); });
            start.countDown();
            assertThat(second.get().getEventId()).isEqualTo(first.get().getEventId());
        } finally { executor.shutdownNow(); }
    }
}
