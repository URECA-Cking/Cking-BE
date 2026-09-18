package kr.co.cking.mission;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.MissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.application.dto.EarnResultCode;
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
 * 동일 Business Key(userId+creatorId+missionId+periodKey)로 동시에 서로 다른
 * requestId가 들어와도, 저장 주체는 이제 이 API가 아니라 {@code ticket-earn.lua}의
 * 멱등키·중복 적립 가드다. 그래서 이 테스트는 DB UNIQUE 경쟁이 아니라 Lua 가드가
 * 실제 Redis에서 정확히 하나만 EARN_ACCEPTED로 통과시키는지를 검증한다. 로컬
 * MySQL·Redis가 떠 있어야 한다.
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

    @Test
    void 동일_business_key로_동시_요청해도_EARN_ACCEPTED와_DUPLICATE_MISSION이_정확히_하나씩_나온다() throws Exception {
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
                    .allMatch(result -> result.equals(EarnResultCode.EARN_ACCEPTED.name())
                            || result.equals(MissionErrorCode.DUPLICATE_MISSION.code()));
            assertThat(results).filteredOn(EarnResultCode.EARN_ACCEPTED.name()::equals).hasSize(1);
            assertThat(results).filteredOn(MissionErrorCode.DUPLICATE_MISSION.code()::equals).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

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
