package kr.co.cking.calendar.application;

import kr.co.cking.calendar.domain.MemberCalendarEntry;
import kr.co.cking.calendar.repository.MemberCalendarEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** MemberCalendarEntry INSERT를 독립 트랜잭션에서 실행해 중복 담기 충돌을 호출자 트랜잭션과 분리한다. */
@Service
@RequiredArgsConstructor
class MemberCalendarEntryPersistenceService {

    private final MemberCalendarEntryRepository entryRepository;

    /** uk_member_calendar_entry_member_schedule UNIQUE 제약 위반을 즉시 확정하도록 flush한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(Long memberId, Long scheduleId, Instant createdAt) {
        entryRepository.saveAndFlush(new MemberCalendarEntry(memberId, scheduleId, createdAt));
    }
}
