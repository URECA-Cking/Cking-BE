package kr.co.cking.mission.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MissionBackfillRunner}가 Creator마다 독립된 트랜잭션으로 처리하는지
 * 실제 MySQL로 검증한다. 존재하지 않는 creatorId를 섞어 그 항목에서만
 * {@code fk_mission_creator} 위반이 나도록 강제하고, 앞뒤로 처리된 실제
 * Creator들의 커밋이 그 실패에 영향받지 않는지 확인한다 — 하나의 트랜잭션으로
 * 묶여 있었다면 이 실패가 전부를 롤백시켰을 것이다. 로컬 MySQL·Redis가 떠
 * 있어야 한다.
 */
@SpringBootTest
class MissionBackfillRunnerIntegrationTest {

    @Autowired
    private MissionBackfillRunner runner;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private MissionRepository missionRepository;

    @Test
    void 존재하지_않는_creator가_섞여_있어도_다른_creator의_초기화는_커밋된다() {
        Creator before = creatorRepository.saveAndFlush(new Creator(newMemberId(), "격리테스트이전크리에이터"));
        Creator after = creatorRepository.saveAndFlush(new Creator(newMemberId(), "격리테스트이후크리에이터"));
        Long nonExistentCreatorId = -1L;

        runner.backfillCreators(List.of(before.getCreatorId(), nonExistentCreatorId, after.getCreatorId()));

        assertThat(missionsOf(before)).extracting(Mission::getType)
                .containsExactlyInAnyOrder(MissionType.ATTENDANCE, MissionType.LIKE);
        assertThat(missionsOf(after)).extracting(Mission::getType)
                .containsExactlyInAnyOrder(MissionType.ATTENDANCE, MissionType.LIKE);
    }

    private List<Mission> missionsOf(Creator creator) {
        return missionRepository.findByCreatorIdAndTypeIn(
                creator.getCreatorId(), List.of(MissionType.ATTENDANCE, MissionType.LIKE));
    }

    private Long newMemberId() {
        Member member = memberRepository.saveAndFlush(
                new Member("백필격리테스트유저" + System.nanoTime(), null, null, MemberRole.USER));
        return member.getMemberId();
    }
}
