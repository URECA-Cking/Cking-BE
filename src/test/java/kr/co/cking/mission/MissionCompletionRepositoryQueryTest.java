package kr.co.cking.mission;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.domain.MissionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class MissionCompletionRepositoryQueryTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private MissionCompletionRepository completionRepository;

    @Test
    void 완료_조회는_user_creator_mission_집합_periodKey를_모두_제한한다() {
        Member member = memberRepository.saveAndFlush(new Member("미션 조회 사용자", null, null, MemberRole.USER));
        Member otherMember = memberRepository.saveAndFlush(new Member("다른 사용자", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(member.getMemberId(), "조회 Creator"));
        Creator otherCreator = creatorRepository.saveAndFlush(new Creator(otherMember.getMemberId(), "다른 Creator"));
        Mission mission = missionRepository.saveAndFlush(
                new Mission(creator.getCreatorId(), MissionType.ATTENDANCE, 1, null, null));
        Mission otherMissionForCreator = missionRepository.saveAndFlush(
                new Mission(creator.getCreatorId(), MissionType.LIKE, 1, null, null));
        Mission otherMission = missionRepository.saveAndFlush(
                new Mission(otherCreator.getCreatorId(), MissionType.LIKE, 1, null, null));

        assertThat(missionRepository.findByCreatorIdAndTypeIn(
                creator.getCreatorId(), List.of(MissionType.ATTENDANCE, MissionType.LIKE)))
                .extracting(Mission::getMissionId)
                .containsExactlyInAnyOrder(mission.getMissionId(), otherMissionForCreator.getMissionId());

        MissionCompletion matching = completion(member.getMemberId(), creator.getCreatorId(), mission.getMissionId(), "2026-09-16");
        completionRepository.saveAndFlush(matching);
        completionRepository.saveAndFlush(completion(member.getMemberId(), creator.getCreatorId(), mission.getMissionId(), "2026-09-15"));
        completionRepository.saveAndFlush(completion(member.getMemberId(), creator.getCreatorId(), otherMissionForCreator.getMissionId(), "2026-09-16"));
        completionRepository.saveAndFlush(completion(otherMember.getMemberId(), creator.getCreatorId(), mission.getMissionId(), "2026-09-16"));
        completionRepository.saveAndFlush(completion(member.getMemberId(), otherCreator.getCreatorId(), otherMission.getMissionId(), "2026-09-16"));

        List<MissionCompletion> result = completionRepository
                .findAllByMemberIdAndCreatorIdAndMissionIdInAndPeriodKey(
                        member.getMemberId(), creator.getCreatorId(), List.of(mission.getMissionId()), "2026-09-16");

        assertThat(result).extracting(MissionCompletion::getCompletionId)
                .containsExactly(matching.getCompletionId());
    }

    @Test
    void 서비스_전체_출석_중복_조회는_크리에이터와_무관하게_ATTENDANCE만_찾는다() {
        Member member = memberRepository.saveAndFlush(new Member("공용 출석 중복 사용자", null, null, MemberRole.USER));
        Member otherMember = memberRepository.saveAndFlush(new Member("공용 출석 다른 사용자", null, null, MemberRole.USER));
        Member likeOnlyMember = memberRepository.saveAndFlush(new Member("공용 출석 좋아요 전용 사용자", null, null, MemberRole.USER));
        Member previousOnlyMember = memberRepository.saveAndFlush(new Member("공용 출석 전날 사용자", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(member.getMemberId(), "공용 출석 Creator"));
        Creator otherCreator = creatorRepository.saveAndFlush(new Creator(otherMember.getMemberId(), "공용 출석 다른 Creator"));
        Creator likeOnlyCreator = creatorRepository.saveAndFlush(new Creator(likeOnlyMember.getMemberId(), "공용 출석 좋아요 Creator"));
        Creator previousOnlyCreator = creatorRepository.saveAndFlush(new Creator(previousOnlyMember.getMemberId(), "공용 출석 전날 Creator"));
        Mission like = missionRepository.saveAndFlush(
                new Mission(creator.getCreatorId(), MissionType.LIKE, 1, null, null));
        Mission otherAttendance = missionRepository.saveAndFlush(
                new Mission(otherCreator.getCreatorId(), MissionType.ATTENDANCE, 1, null, null));
        Mission likeOnlyMission = missionRepository.saveAndFlush(
                new Mission(likeOnlyCreator.getCreatorId(), MissionType.LIKE, 1, null, null));
        Mission previousOnlyAttendance = missionRepository.saveAndFlush(
                new Mission(previousOnlyCreator.getCreatorId(), MissionType.ATTENDANCE, 1, null, null));

        completionRepository.saveAndFlush(completion(
                member.getMemberId(), creator.getCreatorId(), like.getMissionId(), "2026-09-16"));
        completionRepository.saveAndFlush(completion(
                member.getMemberId(), otherCreator.getCreatorId(), otherAttendance.getMissionId(), "2026-09-16"));
        completionRepository.saveAndFlush(completion(
                likeOnlyMember.getMemberId(), likeOnlyCreator.getCreatorId(), likeOnlyMission.getMissionId(), "2026-09-16"));
        completionRepository.saveAndFlush(completion(
                previousOnlyMember.getMemberId(), previousOnlyCreator.getCreatorId(), previousOnlyAttendance.getMissionId(), "2026-09-15"));

        assertThat(completionRepository.existsByMemberIdAndPeriodKeyAndMissionType(
                member.getMemberId(), "2026-09-16", MissionType.ATTENDANCE.name())).isEqualTo(1L);
        assertThat(completionRepository.existsByMemberIdAndPeriodKeyAndMissionType(
                likeOnlyMember.getMemberId(), "2026-09-16", MissionType.ATTENDANCE.name())).isEqualTo(0L);
        assertThat(completionRepository.existsByMemberIdAndPeriodKeyAndMissionType(
                previousOnlyMember.getMemberId(), "2026-09-16", MissionType.ATTENDANCE.name())).isEqualTo(0L);
    }

    private MissionCompletion completion(Long memberId, Long creatorId, Long missionId, String periodKey) {
        return MissionCompletion.builder()
                .memberId(memberId)
                .creatorId(creatorId)
                .missionId(missionId)
                .periodKey(periodKey)
                .requestId(UUID.randomUUID().toString())
                .payloadFingerprint("0".repeat(64))
                .completedAt(Instant.parse("2026-09-16T23:30:00Z"))
                .build();
    }
}
