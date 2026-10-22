package kr.co.cking.calendar.application;

import kr.co.cking.calendar.domain.CreatorSchedule;
import kr.co.cking.calendar.domain.ScheduleQueryRange;
import kr.co.cking.calendar.repository.CreatorScheduleRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 크리에이터 캘린더의 공개 조회를 처리한다(이슈 #293). 인증 없이 접근할 수 있다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorScheduleQueryService {

    private final CreatorRepository creatorRepository;
    private final CreatorScheduleRepository scheduleRepository;

    public List<CreatorSchedule> findByCreatorId(Long creatorId, Instant from, Instant to) {
        requireCreatorExists(creatorId);
        if (!ScheduleQueryRange.isValid(from, to)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return scheduleRepository.findByCreatorIdAndRange(creatorId, from, to);
    }

    public CreatorSchedule findDetail(Long creatorId, Long scheduleId) {
        requireCreatorExists(creatorId);
        CreatorSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!schedule.getCreatorId().equals(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return schedule;
    }

    private void requireCreatorExists(Long creatorId) {
        if (!creatorRepository.existsById(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
