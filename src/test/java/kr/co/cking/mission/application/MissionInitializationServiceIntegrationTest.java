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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link MissionInitializationService}가 실제 MySQL에서도 동작하는지 확인한다
 * (mock 유닛 테스트로는 uk_mission_creator_type UNIQUE 제약과의 상호작용을
 * 검증할 수 없었다). 로컬 MySQL·Redis가 떠 있어야 한다.
 */
@SpringBootTest
class MissionInitializationServiceIntegrationTest {

    @Autowired
    private MissionInitializationService service;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private MissionRepository missionRepository;

    @Test
    void 초기화는_실제_DB에_출석과_좋아요_미션을_생성한다() {
        Creator creator = creatorRepository.saveAndFlush(
                new Creator(newMember().getMemberId(), "초기화테스트크리에이터"));

        service.initializeDefaultMissions(creator.getCreatorId());

        List<Mission> missions = missionRepository.findByCreatorIdAndTypeIn(
                creator.getCreatorId(), List.of(MissionType.ATTENDANCE, MissionType.LIKE));
        assertThat(missions).extracting(Mission::getType)
                .containsExactlyInAnyOrder(MissionType.ATTENDANCE, MissionType.LIKE);
        assertThat(missions).allSatisfy(mission -> assertThat(mission.getRewardAmount()).isEqualTo(1));
    }

    @Test
    void 이미_초기화된_creator에_다시_호출해도_uk_mission_creator_type_위반_없이_멱등하다() {
        Creator creator = creatorRepository.saveAndFlush(
                new Creator(newMember().getMemberId(), "재호출테스트크리에이터"));
        service.initializeDefaultMissions(creator.getCreatorId());

        assertThatCode(() -> service.initializeDefaultMissions(creator.getCreatorId()))
                .doesNotThrowAnyException();

        List<Mission> missions = missionRepository.findByCreatorIdAndTypeIn(
                creator.getCreatorId(), List.of(MissionType.ATTENDANCE, MissionType.LIKE));
        assertThat(missions).hasSize(2);
    }

    private Member newMember() {
        return memberRepository.saveAndFlush(new Member("미션초기화테스트유저" + System.nanoTime(), null, null, MemberRole.USER));
    }
}
