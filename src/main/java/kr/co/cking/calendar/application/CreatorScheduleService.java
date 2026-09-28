package kr.co.cking.calendar.application;

import kr.co.cking.calendar.application.dto.CreatorScheduleFields;
import kr.co.cking.calendar.domain.CreatorSchedule;
import kr.co.cking.calendar.domain.ScheduleQueryRange;
import kr.co.cking.calendar.domain.ScheduleTimeZones;
import kr.co.cking.calendar.repository.CreatorScheduleRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 크리에이터 본인의 캘린더 일정 생성·수정·삭제·조회를 처리한다(이슈 #293).
 * Creator 여부는 JWT Role이 아니라 {@code creator.member_id} 존재 여부로 판단하고,
 * 일정 소유권은 호출자 Creator와 일정의 {@code creatorId} 일치로 검증한다.
 * 관리자 승인 절차는 없으며 생성 즉시 공개된다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CreatorScheduleService {

    private final CreatorRepository creatorRepository;
    private final CreatorScheduleRepository scheduleRepository;
    private final Clock clock;

    public CreatorSchedule create(Long memberId, CreatorScheduleFields fields) {
        Creator creator = requireCreator(memberId);
        String timeZone = validateAndNormalize(fields);
        return scheduleRepository.save(new CreatorSchedule(
                creator.getCreatorId(), fields.scheduleType(), fields.title().trim(), fields.description(),
                fields.startAt(), fields.endAt(), timeZone, fields.location(), fields.imageUrl(),
                fields.externalUrl(), Instant.now(clock)));
    }

    public CreatorSchedule update(Long memberId, Long scheduleId, CreatorScheduleFields fields) {
        Creator creator = requireCreator(memberId);
        String timeZone = validateAndNormalize(fields);
        CreatorSchedule schedule = requireOwnedSchedule(scheduleId, creator.getCreatorId());
        schedule.update(
                fields.scheduleType(), fields.title().trim(), fields.description(), fields.startAt(), fields.endAt(),
                timeZone, fields.location(), fields.imageUrl(), fields.externalUrl(), Instant.now(clock));
        return schedule;
    }

    public void delete(Long memberId, Long scheduleId) {
        Creator creator = requireCreator(memberId);
        CreatorSchedule schedule = requireOwnedSchedule(scheduleId, creator.getCreatorId());
        scheduleRepository.delete(schedule);
    }

    @Transactional(readOnly = true)
    public List<CreatorSchedule> findMine(Long memberId, Instant from, Instant to) {
        Creator creator = requireCreator(memberId);
        if (!ScheduleQueryRange.isValid(from, to)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return scheduleRepository.findByCreatorIdAndRange(creator.getCreatorId(), from, to);
    }

    private String validateAndNormalize(CreatorScheduleFields fields) {
        if (fields.scheduleType() == null || fields.title() == null || fields.title().isBlank()
                || fields.startAt() == null || fields.endAt() == null || !fields.startAt().isBefore(fields.endAt())
                || !ScheduleTimeZones.isValid(fields.timeZone())) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return ScheduleTimeZones.normalize(fields.timeZone());
    }

    private Creator requireCreator(Long memberId) {
        return creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }

    /** 존재하지 않으면 404, 다른 Creator의 일정이면 403 — 존재 여부와 소유권을 구분한다. */
    private CreatorSchedule requireOwnedSchedule(Long scheduleId, Long creatorId) {
        CreatorSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!schedule.getCreatorId().equals(creatorId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return schedule;
    }
}
