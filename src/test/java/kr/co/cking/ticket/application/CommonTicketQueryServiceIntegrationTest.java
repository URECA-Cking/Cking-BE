package kr.co.cking.ticket.application;

import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.CommonMissionRepository;
import kr.co.cking.mission.application.CommonMissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.domain.CommonMissionType;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.CommonTicketLedgerPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommonTicketQueryService}의 native SQL(common_ticket_ledger/common_mission_completion
 * 조인)을 실제 MySQL로 검증한다 — 컴파일로는 컬럼·테이블명 오타를 잡을 수 없다.
 * 로컬 MySQL·Redis가 떠 있어야 한다.
 */
@SpringBootTest
class CommonTicketQueryServiceIntegrationTest {

    private static final long AWAIT_TIMEOUT_MILLIS = 8000L;

    @Autowired
    private CommonMissionCompletionService completionService;
    @Autowired
    private CommonTicketQueryService queryService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CommonMissionRepository commonMissionRepository;

    @Test
    void 완료된_공용_미션이_실제_잔액과_이력_조회에_그대로_반영된다() {
        Member member = memberRepository.saveAndFlush(new Member("공용조회통합테스트유저", null, null, MemberRole.USER));
        Long missionId = commonMissionRepository.findByTypeIn(List.of(CommonMissionType.ATTENDANCE))
                .get(0).getMissionId();

        completionService.complete(missionId, new MissionCompleteCommand(member.getMemberId(), UUID.randomUUID()));

        awaitBalanceAtLeast(member.getMemberId(), 1L);

        CommonTicketBalanceResponse balance = queryService.getBalance(member.getMemberId());
        assertThat(balance.userId()).isEqualTo(member.getMemberId());
        assertThat(balance.balance()).isEqualTo(1L);
        assertThat(balance.updatedAt()).isNotNull();

        CommonTicketLedgerPage page = queryService.getLedger(member.getMemberId(), 20, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).type()).isEqualTo("EARN");
        assertThat(page.items().get(0).deltaAmount()).isEqualTo(1L);
        assertThat(page.items().get(0).missionId()).isEqualTo(missionId);
        assertThat(page.items().get(0).eventId()).isNull();
        assertThat(page.hasNext()).isFalse();
    }

    private void awaitBalanceAtLeast(Long memberId, long expected) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            if (queryService.getBalance(memberId).balance() >= expected) {
                return;
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
