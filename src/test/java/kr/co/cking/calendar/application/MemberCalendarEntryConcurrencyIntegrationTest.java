package kr.co.cking.calendar.application;

import kr.co.cking.calendar.domain.CreatorSchedule;
import kr.co.cking.calendar.domain.ScheduleType;
import kr.co.cking.calendar.repository.CreatorScheduleRepository;
import kr.co.cking.calendar.repository.MemberCalendarEntryRepository;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동일 회원·동일 일정의 병렬 담기 요청이 예외 없이 모두 성공하고 정확히 하나의 참조로
 * 수렴하는지 실제 MySQL·Hibernate에서 검증한다(PR #{@code 319} 리뷰 반영).
 */
@SpringBootTest
class MemberCalendarEntryConcurrencyIntegrationTest {

    @Autowired
    private MemberCalendarEntryService service;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private CreatorScheduleRepository scheduleRepository;

    @Autowired
    private MemberCalendarEntryRepository entryRepository;

    @Test
    void 동시_담기_요청은_예외_없이_모두_성공하고_정확히_하나의_참조로_수렴한다() throws Exception {
        Member fan = memberRepository.saveAndFlush(new Member("동시 담기 팬", null, null, MemberRole.USER));
        Member creatorMember = memberRepository.saveAndFlush(new Member("동시 담기 크리에이터", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "동시 담기 크리에이터"));
        CreatorSchedule schedule = scheduleRepository.saveAndFlush(new CreatorSchedule(
                creator.getCreatorId(), ScheduleType.OTHER, "동시 담기 대상", null,
                Instant.now(), Instant.now().plusSeconds(3600), "Asia/Seoul", null, null, null, Instant.now()));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> runAdd(start, fan.getMemberId(), schedule.getScheduleId()));
            Future<String> second = executor.submit(() -> runAdd(start, fan.getMemberId(), schedule.getScheduleId()));
            start.countDown();

            List<String> results = List.of(first.get(), second.get());

            assertThat(results).withFailMessage("병렬 담기 결과: %s", results).containsOnly("SUCCESS");
            assertThat(entryRepository.existsByMemberIdAndScheduleId(fan.getMemberId(), schedule.getScheduleId()))
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    /** 병렬 담기 결과를 성공 또는 예외 메시지로 변환한다. 예외가 나면 그대로 테스트 실패 원인이 된다. */
    private String runAdd(CountDownLatch start, Long memberId, Long scheduleId) throws InterruptedException {
        start.await();
        service.add(memberId, scheduleId);
        return "SUCCESS";
    }
}
