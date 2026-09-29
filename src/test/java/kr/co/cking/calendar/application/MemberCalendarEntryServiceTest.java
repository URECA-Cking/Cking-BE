package kr.co.cking.calendar.application;

import kr.co.cking.calendar.repository.CreatorScheduleRepository;
import kr.co.cking.calendar.repository.MemberCalendarEntryRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberCalendarEntryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private static final Long MEMBER_ID = 10L;
    private static final Long SCHEDULE_ID = 5L;

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private CreatorScheduleRepository scheduleRepository;

    @Mock
    private MemberCalendarEntryRepository entryRepository;

    @Mock
    private MemberCalendarEntryPersistenceService entryPersistenceService;

    private MemberCalendarEntryService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new MemberCalendarEntryService(
                memberQueryService, scheduleRepository, entryRepository, entryPersistenceService, clock);
    }

    @Test
    void 존재하지_않는_Member가_담으려_하면_RESOURCE_NOT_FOUND다() {
        BusinessException exception = new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        doThrow(exception).when(memberQueryService).validateExists(MEMBER_ID);

        assertThatThrownBy(() -> service.add(MEMBER_ID, SCHEDULE_ID)).isSameAs(exception);

        verifyNoInteractions(scheduleRepository, entryRepository, entryPersistenceService);
    }

    @Test
    void 존재하지_않는_일정을_담으면_RESOURCE_NOT_FOUND다() {
        when(scheduleRepository.existsById(SCHEDULE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.add(MEMBER_ID, SCHEDULE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(entryPersistenceService);
    }

    @Test
    void 정상_담기는_별도_트랜잭션의_영속화_서비스를_호출한다() {
        when(scheduleRepository.existsById(SCHEDULE_ID)).thenReturn(true);
        when(entryRepository.existsByMemberIdAndScheduleId(MEMBER_ID, SCHEDULE_ID)).thenReturn(false);

        service.add(MEMBER_ID, SCHEDULE_ID);

        verify(entryPersistenceService).create(MEMBER_ID, SCHEDULE_ID, NOW);
    }

    @Test
    void 이미_담긴_일정을_다시_담아도_영속화_서비스를_호출하지_않고_성공한다() {
        when(scheduleRepository.existsById(SCHEDULE_ID)).thenReturn(true);
        when(entryRepository.existsByMemberIdAndScheduleId(MEMBER_ID, SCHEDULE_ID)).thenReturn(true);

        service.add(MEMBER_ID, SCHEDULE_ID);

        verifyNoInteractions(entryPersistenceService);
    }

    @Test
    void 동시_중복_담기_요청으로_유니크_제약을_위반해도_실제로_담겨_있으면_멱등_성공으로_처리한다() {
        when(scheduleRepository.existsById(SCHEDULE_ID)).thenReturn(true);
        // 저장 전 확인(false) 이후 저장이 유니크 제약으로 실패하고, catch 안에서 재확인했을 때는
        // 경쟁하던 다른 요청이 이미 커밋되어 true — 진짜 중복이므로 멱등 성공으로 처리한다.
        // 실패한 INSERT는 별도 REQUIRES_NEW 트랜잭션에서 이미 완전히 롤백된 뒤이므로, 이 재확인은
        // 이 메서드 자신의(오염되지 않은) 트랜잭션에서 안전하게 수행된다.
        when(entryRepository.existsByMemberIdAndScheduleId(MEMBER_ID, SCHEDULE_ID)).thenReturn(false, true);
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(entryPersistenceService).create(MEMBER_ID, SCHEDULE_ID, NOW);

        service.add(MEMBER_ID, SCHEDULE_ID);
    }

    @Test
    void 담기_저장_중_유니크_제약이_아닌_다른_제약을_위반하면_예외를_그대로_던진다() {
        when(scheduleRepository.existsById(SCHEDULE_ID)).thenReturn(true);
        // 저장 전후 모두 false — uk_member_calendar_entry_member_schedule 위반이 아니라 다른
        // 원인(예: 저장 순간 일정이 삭제되어 fk_member_calendar_entry_schedule 위반)이므로
        // 멱등 성공으로 감춰서는 안 되고 예외를 그대로 전달해야 한다.
        when(entryRepository.existsByMemberIdAndScheduleId(MEMBER_ID, SCHEDULE_ID)).thenReturn(false, false);
        DataIntegrityViolationException exception = new DataIntegrityViolationException("fk violation");
        doThrow(exception).when(entryPersistenceService).create(MEMBER_ID, SCHEDULE_ID, NOW);

        assertThatThrownBy(() -> service.add(MEMBER_ID, SCHEDULE_ID)).isSameAs(exception);
    }

    @Test
    void 존재하지_않는_Member가_제거하려_하면_RESOURCE_NOT_FOUND다() {
        BusinessException exception = new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        doThrow(exception).when(memberQueryService).validateExists(MEMBER_ID);

        assertThatThrownBy(() -> service.remove(MEMBER_ID, SCHEDULE_ID)).isSameAs(exception);

        verifyNoInteractions(entryRepository);
    }

    @Test
    void 제거는_담겨_있지_않아도_delete를_호출하고_성공한다() {
        // deleteByMemberIdAndScheduleId는 대상이 없어도 0건 삭제로 조용히 끝난다(멱등).
        service.remove(MEMBER_ID, SCHEDULE_ID);

        verify(entryRepository).deleteByMemberIdAndScheduleId(MEMBER_ID, SCHEDULE_ID);
    }
}
