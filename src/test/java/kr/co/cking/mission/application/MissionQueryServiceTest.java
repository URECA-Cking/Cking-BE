package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionCompletion;
import kr.co.cking.mission.MissionCompletionRepository;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.application.dto.MissionQueryItem;
import kr.co.cking.mission.domain.MissionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionQueryServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long CREATOR_ID = 11L;
    private static final Instant NOW = Instant.parse("2026-09-16T23:30:00Z");
    private static final String UTC_PERIOD_KEY = "2026-09-16";

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final MissionRepository missionRepository = mock(MissionRepository.class);
    private final MissionCompletionRepository completionRepository = mock(MissionCompletionRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private MissionQueryService service;

    @BeforeEach
    void setUp() {
        service = new MissionQueryService(
                memberRepository, creatorRepository, missionRepository, completionRepository, clock);
        when(memberRepository.existsById(USER_ID)).thenReturn(true);
        when(creatorRepository.existsById(CREATOR_ID)).thenReturn(true);
    }

    @Test
    void 해당_creator의_활성_미션만_반환하고_utc_완료기록을_한번에_조회한다() {
        Mission startsNow = mission(101L, MissionType.ATTENDANCE, NOW, NOW.plusSeconds(60));
        Mission noBounds = mission(102L, MissionType.LIKE, null, null);
        Mission endsNow = mission(103L, MissionType.ATTENDANCE, NOW.minusSeconds(60), NOW);
        Mission startsLater = mission(104L, MissionType.LIKE, NOW.plusSeconds(1), null);
        when(missionRepository.findByCreatorIdAndTypeIn(eq(CREATOR_ID), argThat(types ->
                types.containsAll(Set.of(MissionType.ATTENDANCE, MissionType.LIKE)))))
                .thenReturn(List.of(startsNow, noBounds, endsNow, startsLater));
        when(completionRepository.findAllByMemberIdAndCreatorIdAndMissionIdInAndPeriodKey(
                eq(USER_ID), eq(CREATOR_ID), argThat(ids -> Set.copyOf(ids).equals(Set.of(101L, 102L))),
                eq(UTC_PERIOD_KEY)))
                .thenReturn(List.of(completion(101L, USER_ID, CREATOR_ID, UTC_PERIOD_KEY)));

        List<MissionQueryItem> result = service.findMissions(CREATOR_ID, USER_ID);

        assertThat(result).extracting(MissionQueryItem::missionId).containsExactly(101L, 102L);
        assertThat(result).extracting(MissionQueryItem::completedToday).containsExactly(true, false);
        assertThat(result.getFirst().activeFrom()).isEqualTo(NOW);
        assertThat(result.getFirst().activeTo()).isEqualTo(NOW.plusSeconds(60));
        verify(completionRepository).findAllByMemberIdAndCreatorIdAndMissionIdInAndPeriodKey(
                eq(USER_ID), eq(CREATOR_ID), argThat(ids -> Set.copyOf(ids).equals(Set.of(101L, 102L))),
                eq(UTC_PERIOD_KEY));
    }

    @Test
    void 존재하지_않는_사용자는_공통_RESOURCE_NOT_FOUND다() {
        when(memberRepository.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.findMissions(CREATOR_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        verify(creatorRepository, never()).existsById(CREATOR_ID);
        verify(missionRepository, never()).findByCreatorIdAndTypeIn(eq(CREATOR_ID), org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void 존재하지_않는_creator는_공통_RESOURCE_NOT_FOUND다() {
        when(creatorRepository.existsById(CREATOR_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.findMissions(CREATOR_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        verify(missionRepository, never()).findByCreatorIdAndTypeIn(eq(CREATOR_ID), org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void 활성_미션이_없으면_완료_이력을_조회하지_않는다() {
        when(missionRepository.findByCreatorIdAndTypeIn(eq(CREATOR_ID), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(mission(105L, MissionType.ATTENDANCE, null, NOW)));

        assertThat(service.findMissions(CREATOR_ID, USER_ID)).isEmpty();

        verify(completionRepository, never()).findAllByMemberIdAndCreatorIdAndMissionIdInAndPeriodKey(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.any());
    }

    private Mission mission(Long missionId, MissionType type, Instant activeFrom, Instant activeTo) {
        Mission mission = new Mission(CREATOR_ID, type, 3, activeFrom, activeTo);
        ReflectionTestUtils.setField(mission, "missionId", missionId);
        return mission;
    }

    private MissionCompletion completion(Long missionId, Long memberId, Long creatorId, String periodKey) {
        return MissionCompletion.builder()
                .missionId(missionId)
                .memberId(memberId)
                .creatorId(creatorId)
                .periodKey(periodKey)
                .requestId("request-" + missionId)
                .payloadFingerprint("fingerprint-" + missionId)
                .completedAt(NOW)
                .build();
    }
}
