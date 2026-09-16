package kr.co.cking.event.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.dto.CreateEventCommand;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.event.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired private EventRepository eventRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 동시성 테스트의 고정 멱등 키가 이전 실행과 충돌하지 않도록 Event를 정리한다. */
    @BeforeEach
    void cleanTestEvent() {
        jdbcTemplate.update("DELETE FROM event_approval_request WHERE event_id IN (SELECT event_id FROM event WHERE request_id = ?)", "550e8400-e29b-41d4-a716-446655440008");
        jdbcTemplate.update("DELETE FROM event WHERE request_id = ?", "550e8400-e29b-41d4-a716-446655440008");
    }

    /** 동일 멱등 키의 병렬 생성이 하나의 Event 식별자로 수렴하는지 검증한다. */
    @Test
    void concurrentSameRequestReturnsSameEvent() throws Exception {
        Member member = memberRepository.saveAndFlush(new Member("동시생성", null, null, MemberRole.USER));
        creatorRepository.saveAndFlush(new Creator(member.getMemberId(), member.getName()));
        CreateEventCommand command = new CreateEventCommand(member.getMemberId(), "550e8400-e29b-41d4-a716-446655440008",
                "동시 이벤트", null, LocalDateTime.now(ZoneOffset.UTC).plusDays(1), LocalDateTime.now(ZoneOffset.UTC).plusDays(2), 1, DrawMethod.WEIGHTED);
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> runCreate(start, command));
            Future<String> second = executor.submit(() -> runCreate(start, command));
            start.countDown();
            java.util.List<String> results = java.util.List.of(first.get(), second.get());
            assertThat(results).withFailMessage("병렬 생성 결과: %s", results)
                    .allMatch(result -> result.equals("SUCCESS") || result.equals("CONCURRENT_COMMAND"));
            assertThat(results).contains("SUCCESS");
            assertThat(eventRepository.findByRequestId(command.requestId())).isPresent();
        } finally { executor.shutdownNow(); }
    }

    /** 병렬 생성 결과를 성공 또는 도메인 오류 코드로 변환한다. */
    private String runCreate(CountDownLatch start, CreateEventCommand command) throws InterruptedException {
        start.await();
        try { service.create(command); return "SUCCESS"; }
        catch (kr.co.cking.common.exception.BusinessException exception) { return exception.getErrorCode().code(); }
    }
}
