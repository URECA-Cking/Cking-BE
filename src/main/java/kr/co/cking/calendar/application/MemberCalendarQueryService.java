package kr.co.cking.calendar.application;

import kr.co.cking.calendar.domain.ScheduleQueryRange;
import kr.co.cking.calendar.repository.MemberCalendarEntryRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 사용자가 개인 캘린더에 담은 일정을 기간으로 조회한다(이슈 #319). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberCalendarQueryService {

    private final MemberQueryService memberQueryService;
    private final MemberCalendarEntryRepository entryRepository;

    /** 담은 일정 중 from~to와 겹치는 일정을 크리에이터 이름과 함께 시작 시각 오름차순으로 반환한다. */
    public List<MemberCalendarScheduleResult> findMine(Long memberId, Instant from, Instant to) {
        memberQueryService.validateExists(memberId);
        if (!ScheduleQueryRange.isValid(from, to)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return entryRepository.findSchedulesByMemberIdAndRange(memberId, from, to).stream()
                .map(MemberCalendarScheduleResult::from)
                .toList();
    }
}
