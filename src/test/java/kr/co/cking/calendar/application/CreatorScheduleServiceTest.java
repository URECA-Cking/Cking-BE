package kr.co.cking.calendar.application;

import kr.co.cking.calendar.application.dto.CreatorScheduleFields;
import kr.co.cking.calendar.domain.CreatorSchedule;
import kr.co.cking.calendar.domain.ScheduleType;
import kr.co.cking.calendar.repository.CreatorScheduleRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CreatorScheduleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final Long MEMBER_ID = 10L;
    private static final Long CREATOR_ID = 1L;

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorScheduleRepository scheduleRepository = mock(CreatorScheduleRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CreatorScheduleService service =
            new CreatorScheduleService(memberRepository, creatorRepository, scheduleRepository, clock);

    @BeforeEach
    void setUpMember() {
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member()));
    }

    @Test
    void 존재하지_않는_Member면_RESOURCE_NOT_FOUND다() {
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(MEMBER_ID, validFields()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void Member는_있지만_크리에이터가_아니면_생성_시_FORBIDDEN이다() {
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(MEMBER_ID, validFields()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void 정상_생성은_timeZone을_정규화해서_저장한다() {
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator()));
        given(scheduleRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        CreatorSchedule saved = service.create(MEMBER_ID, validFields());

        ArgumentCaptor<CreatorSchedule> captor = ArgumentCaptor.forClass(CreatorSchedule.class);
        verify(scheduleRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatorId()).isEqualTo(CREATOR_ID);
        assertThat(captor.getValue().getTimeZone()).isEqualTo("Asia/Seoul");
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void startAt이_endAt보다_늦으면_VALIDATION_FAILED다() {
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator()));
        CreatorScheduleFields fields = new CreatorScheduleFields(
                ScheduleType.FAN_SIGN, "제목", null,
                Instant.parse("2026-10-10T10:00:00Z"), Instant.parse("2026-10-10T09:00:00Z"),
                "Asia/Seoul", null, null, null);

        assertThatThrownBy(() -> service.create(MEMBER_ID, fields))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void timeZone이_오프셋_표기면_VALIDATION_FAILED다() {
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator()));
        CreatorScheduleFields fields = new CreatorScheduleFields(
                ScheduleType.FAN_SIGN, "제목", null,
                Instant.parse("2026-10-10T09:00:00Z"), Instant.parse("2026-10-10T10:00:00Z"),
                "+09:00", null, null, null);

        assertThatThrownBy(() -> service.create(MEMBER_ID, fields))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 다른_크리에이터의_일정을_수정하면_FORBIDDEN이다() {
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator()));
        CreatorSchedule othersSchedule = new CreatorSchedule(
                999L, ScheduleType.OTHER, "다른 사람 일정", null,
                Instant.parse("2026-10-10T09:00:00Z"), Instant.parse("2026-10-10T10:00:00Z"),
                "Asia/Seoul", null, null, null, NOW);
        given(scheduleRepository.findById(5L)).willReturn(Optional.of(othersSchedule));

        assertThatThrownBy(() -> service.update(MEMBER_ID, 5L, validFields()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    void 존재하지_않는_일정을_수정하면_RESOURCE_NOT_FOUND다() {
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator()));
        given(scheduleRepository.findById(5L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(MEMBER_ID, 5L, validFields()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 본인_소유_일정_수정은_전체_필드를_교체한다() {
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator()));
        CreatorSchedule mine = new CreatorSchedule(
                CREATOR_ID, ScheduleType.OTHER, "기존 제목", "기존 설명",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T01:00:00Z"),
                "Asia/Seoul", null, null, null, NOW);
        given(scheduleRepository.findById(5L)).willReturn(Optional.of(mine));

        CreatorSchedule result = service.update(MEMBER_ID, 5L, validFields());

        assertThat(result.getTitle()).isEqualTo(validFields().title());
        assertThat(result.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void 삭제는_소유권_확인_후_repository_delete를_호출한다() {
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator()));
        CreatorSchedule mine = new CreatorSchedule(
                CREATOR_ID, ScheduleType.OTHER, "제목", null,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T01:00:00Z"),
                "Asia/Seoul", null, null, null, NOW);
        given(scheduleRepository.findById(5L)).willReturn(Optional.of(mine));

        service.delete(MEMBER_ID, 5L);

        verify(scheduleRepository).delete(mine);
    }

    @Test
    void 조회_기간이_1년을_초과하면_VALIDATION_FAILED다() {
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator()));
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = from.plusSeconds(366L * 24 * 3600);

        assertThatThrownBy(() -> service.findMine(MEMBER_ID, from, to))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 정상_기간_조회는_repository_결과를_그대로_반환한다() {
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator()));
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T00:00:00Z");
        CreatorSchedule schedule = new CreatorSchedule(
                CREATOR_ID, ScheduleType.OTHER, "제목", null, from, to, "Asia/Seoul", null, null, null, NOW);
        given(scheduleRepository.findByCreatorIdAndRange(CREATOR_ID, from, to)).willReturn(List.of(schedule));

        assertThat(service.findMine(MEMBER_ID, from, to)).containsExactly(schedule);
    }

    private CreatorScheduleFields validFields() {
        return new CreatorScheduleFields(
                ScheduleType.FAN_SIGN, "서울 팬사인회", "설명",
                Instant.parse("2026-10-10T05:00:00Z"), Instant.parse("2026-10-10T07:00:00Z"),
                "Asia/Seoul", "서울", "https://img/1.png", "https://example.com");
    }

    private Creator creator() {
        Creator creator = new Creator(MEMBER_ID, "테스트 크리에이터");
        org.springframework.test.util.ReflectionTestUtils.setField(creator, "creatorId", CREATOR_ID);
        return creator;
    }

    private Member member() {
        return new Member("테스트 회원", null, null, MemberRole.USER);
    }
}
