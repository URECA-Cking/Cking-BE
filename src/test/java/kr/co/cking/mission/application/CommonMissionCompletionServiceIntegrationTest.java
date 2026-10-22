package kr.co.cking.mission.application;

import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.CommonMissionRepository;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.CommonMissionType;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이슈 #219 완료 조건: "공용 미션 완료 시 EARN을 통해 공용 잔액에 정확히 적립되며,
 * 하루 중복 적립 방지가 크리에이터별 미션과 동등하게 적용된다"를 mock이 아니라
 * 실제 Redis Lua + Stream + Consumer로 끝까지(end-to-end) 검증한다. 로컬 MySQL·
 * Redis가 떠 있어야 한다.
 */
@SpringBootTest
class CommonMissionCompletionServiceIntegrationTest {

    private static final long AWAIT_TIMEOUT_MILLIS = 8000L;

    @Autowired
    private CommonMissionCompletionService service;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CommonMissionRepository commonMissionRepository;
    @Autowired
    private UserCommonTicketBalanceRepository userCommonTicketBalanceRepository;

    @Test
    void 공용_미션_완료는_실제_공용_잔액에_적립되고_같은_날_재시도는_재적립하지_않는다() {
        Member member = memberRepository.saveAndFlush(new Member("공용미션통합테스트유저", null, null, MemberRole.USER));
        Long missionId = commonMissionRepository.findByTypeIn(List.of(CommonMissionType.ATTENDANCE))
                .get(0).getMissionId();
        UUID requestId = UUID.randomUUID();

        MissionCompleteOutcome first = service.complete(
                missionId, new MissionCompleteCommand(member.getMemberId(), requestId));
        assertThat(first.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);

        long balanceAfterFirst = awaitBalanceAtLeast(member.getMemberId(), 1L);
        assertThat(balanceAfterFirst).isEqualTo(1L);

        // 같은 requestId 재시도 — 실제 Redis Lua 가드가 재적립을 막는지 확인한다.
        MissionCompleteOutcome retry = service.complete(
                missionId, new MissionCompleteCommand(member.getMemberId(), requestId));
        assertThat(retry.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);

        // Consumer가 재적립 메시지를 만들지 않았을 것이므로 잠깐 대기해도 잔액은 그대로여야 한다.
        assertThat(currentBalance(member.getMemberId())).isEqualTo(1L);
    }

    private long currentBalance(Long memberId) {
        return userCommonTicketBalanceRepository.findById(memberId)
                .map(UserCommonTicketBalance::getBalance)
                .orElse(0L);
    }

    private long awaitBalanceAtLeast(Long memberId, long expected) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            Optional<UserCommonTicketBalance> balance = userCommonTicketBalanceRepository.findById(memberId);
            if (balance.isPresent() && balance.get().getBalance() >= expected) {
                return balance.get().getBalance();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        throw new AssertionError("공용 EARN이 제한 시간 내에 잔액에 반영되지 않았습니다. memberId=" + memberId);
    }
}
