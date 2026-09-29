package kr.co.cking.calendar.application;

import kr.co.cking.calendar.domain.CreatorSchedule;
import kr.co.cking.calendar.domain.ScheduleType;
import kr.co.cking.calendar.repository.CreatorScheduleRepository;
import kr.co.cking.calendar.repository.MemberCalendarEntryRepository;
import kr.co.cking.common.exception.BusinessException;
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

    /**
     * 담기 도중 대상 일정이 다른 스레드에서 하드 삭제되면, 두 결과(담기 성공 또는
     * RESOURCE_NOT_FOUND) 중 하나로만 수렴하고 500(SYSTEM_ERROR)으로 노출되지 않는지
     * 검증한다(PR #319 팀원 리뷰 반영). 정확한 경합 시점을 강제할 수 없어 여러 번 반복해
     * 실제로 경합이 발생할 확률을 높인다.
     */
    @Test
    void 담기와_동시에_일정이_하드_삭제되면_성공하거나_RESOURCE_NOT_FOUND로_수렴한다() throws Exception {
        Member fan = memberRepository.saveAndFlush(new Member("삭제 경합 팬", null, null, MemberRole.USER));
        Member creatorMember = memberRepository.saveAndFlush(new Member("삭제 경합 크리에이터", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "삭제 경합 크리에이터"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 30; i++) {
                CreatorSchedule schedule = scheduleRepository.saveAndFlush(new CreatorSchedule(
                        creator.getCreatorId(), ScheduleType.OTHER, "삭제 경합 대상 " + i, null,
                        Instant.now(), Instant.now().plusSeconds(3600), "Asia/Seoul", null, null, null, Instant.now()));
                Long scheduleId = schedule.getScheduleId();

                CountDownLatch start = new CountDownLatch(1);
                Future<String> addResult = executor.submit(() -> runAddOrErrorCode(start, fan.getMemberId(), scheduleId));
                Future<Void> deleteResult = executor.submit(() -> runDelete(start, scheduleId));
                start.countDown();

                String outcome = addResult.get();
                deleteResult.get();

                assertThat(outcome).withFailMessage("담기 결과: %s (scheduleId=%s)", outcome, scheduleId)
                        .isIn("SUCCESS", "RESOURCE_NOT_FOUND");
            }
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

    /** 병렬 담기 결과를 성공 또는 도메인 오류 코드로 변환한다. */
    private String runAddOrErrorCode(CountDownLatch start, Long memberId, Long scheduleId) throws InterruptedException {
        start.await();
        try {
            service.add(memberId, scheduleId);
            return "SUCCESS";
        } catch (BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }

    /**
     * 대상 일정이 남아 있으면 하드 삭제한다. 이 테스트 메서드 자신은 트랜잭션이 없으므로
     * delete() 호출 자체가 Spring Data Repository의 기본 트랜잭션으로 즉시 커밋된다.
     */
    private Void runDelete(CountDownLatch start, Long scheduleId) throws InterruptedException {
        start.await();
        scheduleRepository.findById(scheduleId).ifPresent(scheduleRepository::delete);
        return null;
    }
}
