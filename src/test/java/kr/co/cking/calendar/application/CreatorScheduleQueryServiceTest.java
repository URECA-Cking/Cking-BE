package kr.co.cking.calendar.application;

import kr.co.cking.calendar.domain.CreatorSchedule;
import kr.co.cking.calendar.domain.ScheduleType;
import kr.co.cking.calendar.repository.CreatorScheduleRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CreatorScheduleQueryServiceTest {

    private static final Long CREATOR_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorScheduleRepository scheduleRepository = mock(CreatorScheduleRepository.class);
    private final CreatorScheduleQueryService service =
            new CreatorScheduleQueryService(creatorRepository, scheduleRepository);

    @Test
    void 존재하지_않는_크리에이터를_조회하면_RESOURCE_NOT_FOUND다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(false);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T00:00:00Z");

        assertThatThrownBy(() -> service.findByCreatorId(CREATOR_ID, from, to))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 조회_기간이_1년을_초과하면_VALIDATION_FAILED다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = from.plusSeconds(366L * 24 * 3600);

        assertThatThrownBy(() -> service.findByCreatorId(CREATOR_ID, from, to))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 정상_기간_조회는_repository_결과를_그대로_반환한다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T00:00:00Z");
        CreatorSchedule schedule = new CreatorSchedule(
                CREATOR_ID, ScheduleType.OTHER, "제목", null, from, to, "Asia/Seoul", null, null, null, NOW);
        given(scheduleRepository.findByCreatorIdAndRange(CREATOR_ID, from, to)).willReturn(List.of(schedule));

        assertThat(service.findByCreatorId(CREATOR_ID, from, to)).containsExactly(schedule);
    }

    @Test
    void 다른_크리에이터의_일정_상세를_조회하면_RESOURCE_NOT_FOUND다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);
        CreatorSchedule othersSchedule = new CreatorSchedule(
                999L, ScheduleType.OTHER, "다른 크리에이터 일정", null,
                Instant.parse("2026-10-10T09:00:00Z"), Instant.parse("2026-10-10T10:00:00Z"),
                "Asia/Seoul", null, null, null, NOW);
        given(scheduleRepository.findById(5L)).willReturn(Optional.of(othersSchedule));

        assertThatThrownBy(() -> service.findDetail(CREATOR_ID, 5L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 존재하지_않는_일정_상세를_조회하면_RESOURCE_NOT_FOUND다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);
        given(scheduleRepository.findById(5L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findDetail(CREATOR_ID, 5L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 소유한_일정_상세를_반환한다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);
        CreatorSchedule schedule = new CreatorSchedule(
                CREATOR_ID, ScheduleType.OTHER, "제목", null,
                Instant.parse("2026-10-10T09:00:00Z"), Instant.parse("2026-10-10T10:00:00Z"),
                "Asia/Seoul", null, null, null, NOW);
        given(scheduleRepository.findById(5L)).willReturn(Optional.of(schedule));

        assertThat(service.findDetail(CREATOR_ID, 5L)).isEqualTo(schedule);
    }
}
