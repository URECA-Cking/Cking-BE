package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.util.ArrayList;
import java.util.List;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.repository.CreatorApplicationRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Creator 승인과 기본 Mission 초기화가 하나의 트랜잭션으로 동작하는지 실제 DB에서 검증한다. */
@SpringBootTest
class CreatorApprovalMissionInitializationIntegrationTest {

    @Autowired
    private CreatorApplicationService creatorApplicationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private CreatorApplicationRepository creatorApplicationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private MissionRepository missionRepository;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> applicationIds = new ArrayList<>();

    /** 승인에 성공하면 Creator와 상시 활성 기본 미션 두 개를 함께 저장한다. */
    @Test
    void 승인_시_기본_미션을_함께_생성한다() {
        ApprovalFixture fixture = createApprovalFixture();

        creatorApplicationService.approve(fixture.adminId(), fixture.applicationId());

        Creator creator = creatorRepository.findByMemberId(fixture.applicantId()).orElseThrow();
        List<Mission> missions = missionRepository.findByCreatorIdAndTypeIn(
                creator.getCreatorId(), List.of(MissionType.ATTENDANCE, MissionType.LIKE)
        );

        assertThat(creatorApplicationRepository.findById(fixture.applicationId()).orElseThrow().getStatus())
                .isEqualTo(CreatorApplicationStatus.APPROVED);
        assertThat(missions).hasSize(2).allSatisfy(mission -> {
            assertThat(mission.getRewardAmount()).isEqualTo(1);
            assertThat(mission.getActiveFrom()).isNull();
            assertThat(mission.getActiveTo()).isNull();
        });
        assertThat(missions).extracting(Mission::getType)
                .containsExactlyInAnyOrder(MissionType.ATTENDANCE, MissionType.LIKE);
    }

    /** 미션 저장 실패가 발생하면 Creator 생성과 신청 승인 상태도 모두 롤백한다. */
    @Test
    void 기본_미션_초기화_실패_시_승인을_함께_롤백한다() {
        ApprovalFixture fixture = createApprovalFixture();
        doThrow(new IllegalStateException("기본 미션 저장 강제 실패"))
                .when(missionRepository).save(any(Mission.class));

        assertThatThrownBy(() -> creatorApplicationService.approve(fixture.adminId(), fixture.applicationId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("기본 미션 저장 강제 실패");

        assertThat(creatorRepository.existsByMemberId(fixture.applicantId())).isFalse();
        assertThat(creatorApplicationRepository.findById(fixture.applicationId()).orElseThrow().getStatus())
                .isEqualTo(CreatorApplicationStatus.PENDING);
    }

    /** 테스트 실패 여부와 관계없이 Member 기준으로 연관 데이터를 외래 키 역순으로 제거한다. */
    @AfterEach
    void cleanUp() {
        reset(missionRepository);
        memberIds.forEach(memberId -> {
            jdbcTemplate.update(
                    "DELETE FROM mission WHERE creator_id IN (SELECT creator_id FROM creator WHERE member_id = ?)",
                    memberId
            );
            jdbcTemplate.update("DELETE FROM creator WHERE member_id = ?", memberId);
        });
        applicationIds.forEach(applicationId -> jdbcTemplate.update(
                "DELETE FROM creator_application WHERE id = ?", applicationId));
        memberIds.forEach(memberId -> jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId));
    }

    /** 관리자·신청자·PENDING 신청으로 승인 가능한 테스트 데이터를 만든다. */
    private ApprovalFixture createApprovalFixture() {
        Member admin = memberRepository.saveAndFlush(new Member("미션초기화관리자", null, null, MemberRole.ADMIN));
        Member applicant = memberRepository.saveAndFlush(new Member("미션초기화신청자", null, null, MemberRole.USER));
        CreatorApplication application = creatorApplicationRepository.saveAndFlush(
                new CreatorApplication(applicant.getMemberId())
        );
        memberIds.add(admin.getMemberId());
        memberIds.add(applicant.getMemberId());
        applicationIds.add(application.getId());
        return new ApprovalFixture(admin.getMemberId(), applicant.getMemberId(), application.getId());
    }

    /** 승인 테스트에 필요한 식별자 묶음을 전달한다. */
    private record ApprovalFixture(Long adminId, Long applicantId, Long applicationId) {
    }
}
