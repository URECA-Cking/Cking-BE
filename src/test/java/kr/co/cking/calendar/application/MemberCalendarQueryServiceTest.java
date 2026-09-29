package kr.co.cking.calendar.application;

import kr.co.cking.calendar.domain.ScheduleType;
import kr.co.cking.calendar.repository.MemberCalendarEntryRepository;
import kr.co.cking.calendar.repository.MemberCalendarScheduleProjection;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberCalendarQueryServiceTest {

    private static final Long MEMBER_ID = 10L;

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private MemberCalendarEntryRepository entryRepository;

    private MemberCalendarQueryService service;

    @BeforeEach
    void setUp() {
        service = new MemberCalendarQueryService(memberQueryService, entryRepository);
    }

    @Test
    void 존재하지_않는_Member면_RESOURCE_NOT_FOUND다() {
        BusinessException exception = new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        doThrow(exception).when(memberQueryService).validateExists(MEMBER_ID);
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-31T00:00:00Z");

        assertThatThrownBy(() -> service.findMine(MEMBER_ID, from, to)).isSameAs(exception);

        verifyNoInteractions(entryRepository);
    }

    @Test
    void 조회_기간이_1년을_초과하면_VALIDATION_FAILED다() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = from.plusSeconds(366L * 24 * 3600);

        assertThatThrownBy(() -> service.findMine(MEMBER_ID, from, to))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 정상_조회는_크리에이터_이름을_포함해_결과를_그대로_반환한다() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-31T00:00:00Z");
        Instant startAt = Instant.parse("2026-10-10T05:00:00Z");
        Instant endAt = Instant.parse("2026-10-10T07:00:00Z");
        MemberCalendarScheduleProjection projection = new MemberCalendarScheduleProjection(
                100L, 5L, "테스트 크리에이터", ScheduleType.FAN_SIGN, "서울 팬사인회", "설명",
                startAt, endAt, "Asia/Seoul", "서울", "https://img/1.png", "https://example.com");
        when(entryRepository.findSchedulesByMemberIdAndRange(MEMBER_ID, from, to)).thenReturn(List.of(projection));

        List<MemberCalendarScheduleResult> results = service.findMine(MEMBER_ID, from, to);

        verify(memberQueryService).validateExists(MEMBER_ID);
        assertThat(results).hasSize(1);
        MemberCalendarScheduleResult result = results.get(0);
        assertThat(result.scheduleId()).isEqualTo(100L);
        assertThat(result.creatorId()).isEqualTo(5L);
        assertThat(result.creatorName()).isEqualTo("테스트 크리에이터");
        assertThat(result.scheduleType()).isEqualTo("FAN_SIGN");
        assertThat(result.startAt()).isEqualTo(startAt);
        assertThat(result.endAt()).isEqualTo(endAt);
    }
}
