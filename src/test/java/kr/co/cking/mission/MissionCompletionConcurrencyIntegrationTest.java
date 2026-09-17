package kr.co.cking.mission;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.MissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.domain.Mission;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.mission.repository.MissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB UNIQUE 제약({@code uk_completion_business})이 실제 MySQL에서 동시 삽입을
 * 정확히 하나만 통과시키는지 검증한다. 로컬 MySQL·Redis가 떠 있어야 한다
 * ({@link kr.co.cking.event.application.CreatorEventConcurrencyIntegrationTest}와 동일한 전제).
 */
@SpringBootTest
class MissionCompletionConcurrencyIntegrationTest {

    @Autowired
    private MissionCompletionService service;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private MissionRepository missionRepository;

    /** 같은 Business Key(같은 유저·크리에이터·미션·기간)로 동시에 완료 요청이 들어오면 하나만 성공한다. */
    @Test
    void 동일_business_key로_동시_요청해도_정확히_하나만_성공한다() throws Exception {
        Member member = memberRepository.saveAndFlush(new Member("동시완료테스트", null, null, MemberRole.USER));
        Member creatorOwner = memberRepository.saveAndFlush(new Member("동시완료크리에이터", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(creatorOwner.getMemberId(), creatorOwner.getName()));
        Mission mission = missionRepository.saveAndFlush(
                new Mission(creator.getCreatorId(), MissionType.ATTENDANCE, 1, null, null));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> runComplete(start, member.getMemberId(),
                    creator.getCreatorId(), mission.getMissionId()));
            Future<String> second = executor.submit(() -> runComplete(start, member.getMemberId(),
                    creator.getCreatorId(), mission.getMissionId()));
            start.countDown();

            List<String> results = List.of(first.get(), second.get());

            assertThat(results).withFailMessage("동시 완료 결과: %s", results)
                    .allMatch(result -> result.equals("EARN_ACCEPTED") || result.equals("DUPLICATE_MISSION"));
            assertThat(results).contains("EARN_ACCEPTED");
            assertThat(results.stream().filter("EARN_ACCEPTED"::equals).count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 병렬 완료 요청 결과를 성공 코드 또는 도메인 오류 코드 문자열로 변환한다. */
    private String runComplete(CountDownLatch start, Long userId, Long creatorId, Long missionId)
            throws InterruptedException {
        start.await();
        try {
            return service.complete(creatorId, missionId,
                    new MissionCompleteCommand(userId, UUID.randomUUID())).code().name();
        } catch (BusinessException e) {
            return e.getErrorCode().code();
        }
    }
}
