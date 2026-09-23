package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.CommonMission;
import kr.co.cking.mission.CommonMissionCompletion;
import kr.co.cking.mission.CommonMissionCompletionRepository;
import kr.co.cking.mission.CommonMissionRepository;
import kr.co.cking.mission.application.dto.CommonMissionQueryItem;
import kr.co.cking.mission.domain.CommonMissionType;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommonMissionQueryServiceTest {

    private static final Long USER_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-09-16T23:30:00Z");
    private static final String UTC_PERIOD_KEY = "2026-09-16";

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CommonMissionRepository missionRepository = mock(CommonMissionRepository.class);
    private final CommonMissionCompletionRepository completionRepository = mock(CommonMissionCompletionRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private CommonMissionQueryService service;

    @BeforeEach
    void setUp() {
        service = new CommonMissionQueryService(memberRepository, missionRepository, completionRepository, clock);
        when(memberRepository.existsById(USER_ID)).thenReturn(true);
    }

    @Test
    void 활성_미션만_반환하고_utc_완료기록을_한번에_조회한다() {
        CommonMission active = mission(101L, NOW, NOW.plusSeconds(60));
        CommonMission ended = mission(102L, NOW.minusSeconds(60), NOW);
        when(missionRepository.findByTypeIn(any())).thenReturn(List.of(active, ended));
        when(completionRepository.findAllByMemberIdAndMissionIdInAndPeriodKey(
                eq(USER_ID), argThat(ids -> Set.copyOf(ids).equals(Set.of(101L))), eq(UTC_PERIOD_KEY)))
                .thenReturn(List.of(completion(101L)));

        List<CommonMissionQueryItem> result = service.findMissions(USER_ID);

        assertThat(result).extracting(CommonMissionQueryItem::missionId).containsExactly(101L);
        assertThat(result).extracting(CommonMissionQueryItem::completedToday).containsExactly(true);
    }

    @Test
    void 존재하지_않는_사용자는_공통_RESOURCE_NOT_FOUND다() {
        when(memberRepository.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.findMissions(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        verify(missionRepository, never()).findByTypeIn(any());
    }

    @Test
    void 활성_미션이_없으면_완료_이력을_조회하지_않는다() {
        when(missionRepository.findByTypeIn(any())).thenReturn(List.of(mission(105L, null, NOW)));

        assertThat(service.findMissions(USER_ID)).isEmpty();

        verify(completionRepository, never()).findAllByMemberIdAndMissionIdInAndPeriodKey(any(), any(), any());
    }

    private CommonMission mission(Long missionId, Instant activeFrom, Instant activeTo) {
        CommonMission mission = new CommonMission(CommonMissionType.ATTENDANCE, 3, activeFrom, activeTo);
        ReflectionTestUtils.setField(mission, "missionId", missionId);
        return mission;
    }

    private CommonMissionCompletion completion(Long missionId) {
        return CommonMissionCompletion.builder()
                .missionId(missionId)
                .memberId(USER_ID)
                .periodKey(UTC_PERIOD_KEY)
                .requestId("request-" + missionId)
                .payloadFingerprint("fingerprint-" + missionId)
                .completedAt(NOW)
                .build();
    }
}
