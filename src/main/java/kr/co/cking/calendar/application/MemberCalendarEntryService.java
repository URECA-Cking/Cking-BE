package kr.co.cking.calendar.application;

import kr.co.cking.calendar.domain.MemberCalendarEntry;
import kr.co.cking.calendar.repository.CreatorScheduleRepository;
import kr.co.cking.calendar.repository.MemberCalendarEntryRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** 사용자가 개인 캘린더에 크리에이터 일정을 담고 빼는 명령을 처리한다(이슈 #319). */
@Service
@RequiredArgsConstructor
@Transactional
public class MemberCalendarEntryService {

    private final MemberQueryService memberQueryService;
    private final CreatorScheduleRepository scheduleRepository;
    private final MemberCalendarEntryRepository entryRepository;
    private final Clock clock;

    /** 이미 담긴 일정을 다시 담아도 성공으로 처리한다(멱등). 존재하지 않는 일정은 RESOURCE_NOT_FOUND다. */
    public void add(Long memberId, Long scheduleId) {
        memberQueryService.validateExists(memberId);
        requireScheduleExists(scheduleId);
        if (entryRepository.existsByMemberIdAndScheduleId(memberId, scheduleId)) {
            return;
        }
        try {
            entryRepository.save(new MemberCalendarEntry(memberId, scheduleId, Instant.now(clock)));
        } catch (DataIntegrityViolationException exception) {
            // uk_member_calendar_entry_member_schedule 위반(동시 중복 담기)이면 이미 담겨 있다는
            // 뜻이므로 멱등 성공으로 처리한다. 그 외 제약 위반(예: 저장 순간 일정이 삭제되어
            // fk_member_calendar_entry_schedule 위반)이면 실제로 담기지 않았으므로 그대로 던진다.
            if (!entryRepository.existsByMemberIdAndScheduleId(memberId, scheduleId)) {
                throw exception;
            }
        }
    }

    /** 담겨 있지 않은 일정을 제거해도 성공으로 처리한다(멱등). */
    public void remove(Long memberId, Long scheduleId) {
        memberQueryService.validateExists(memberId);
        entryRepository.deleteByMemberIdAndScheduleId(memberId, scheduleId);
    }

    private void requireScheduleExists(Long scheduleId) {
        if (!scheduleRepository.existsById(scheduleId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
