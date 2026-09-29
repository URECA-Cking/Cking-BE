package kr.co.cking.calendar.repository;

import kr.co.cking.calendar.domain.MemberCalendarEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MemberCalendarEntryRepository extends JpaRepository<MemberCalendarEntry, Long> {

    boolean existsByMemberIdAndScheduleId(Long memberId, Long scheduleId);

    void deleteByMemberIdAndScheduleId(Long memberId, Long scheduleId);

    /**
     * 담은 일정 중 from~to와 조금이라도 겹치는 일정을 크리에이터 이름과 함께 시작 시각 오름차순으로 반환한다.
     * {@code uk_member_calendar_entry_member_schedule(member_id, schedule_id)}의 왼쪽 접두사로
     * {@code member_id} 동등조건을 처리하고, {@code CreatorSchedule}은 PK로, {@code Creator}는
     * 다른 도메인이라 완전한 이름으로 명시해 PK로 조인한다. {@code end_at} 조건은 인덱스로 걸러지지
     * 않는 residual condition이다.
     */
    @Query("""
            select new kr.co.cking.calendar.repository.MemberCalendarScheduleProjection(
                s.scheduleId, s.creatorId, c.name, s.scheduleType, s.title, s.description,
                s.startAt, s.endAt, s.timeZone, s.location, s.imageUrl, s.externalUrl
            )
            from MemberCalendarEntry e
            join CreatorSchedule s on s.scheduleId = e.scheduleId
            join kr.co.cking.creator.domain.Creator c on c.creatorId = s.creatorId
            where e.memberId = :memberId
              and s.startAt < :to
              and s.endAt > :from
            order by s.startAt asc, s.scheduleId asc
            """)
    List<MemberCalendarScheduleProjection> findSchedulesByMemberIdAndRange(
            @Param("memberId") Long memberId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );
}
