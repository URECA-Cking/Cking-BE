package kr.co.cking.calendar.repository;

import kr.co.cking.calendar.domain.CreatorSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CreatorScheduleRepository extends JpaRepository<CreatorSchedule, Long> {

    /**
     * from~to와 조금이라도 겹치는 일정을 시작 시각 오름차순으로 반환한다.
     * {@code idx_creator_schedule_creator_start(creator_id, start_at, schedule_id)}가
     * creator_id 동등조건·start_at 범위조건·정렬까지 처리하고, end_at 조건은 residual로 검사한다.
     */
    @Query("""
            select s from CreatorSchedule s
            where s.creatorId = :creatorId
              and s.startAt < :to
              and s.endAt > :from
            order by s.startAt asc, s.scheduleId asc
            """)
    List<CreatorSchedule> findByCreatorIdAndRange(
            @Param("creatorId") Long creatorId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );
}
