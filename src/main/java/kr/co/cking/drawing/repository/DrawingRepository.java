package kr.co.cking.drawing.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingVisibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DrawingRepository extends JpaRepository<Drawing, Long> {

    Optional<Drawing> findByEventIdAndDrawNo(Long eventId, int drawNo);

    Optional<Drawing> findByEventIdAndDrawNoAndVisibility(Long eventId, int drawNo, DrawingVisibility visibility);

    /** 특정 RedrawRequest가 실제로 실행해 생성한 REDRAW Drawing을 조회한다. */
    Optional<Drawing> findByRedrawRequestId(Long redrawRequestId);

    /** Event의 가장 최근 Drawing을 조회해 다음 REDRAW 회차를 정한다. */
    Optional<Drawing> findTopByEventIdOrderByDrawNoDesc(Long eventId);

    /** Event 행 잠금 뒤 최신 INITIAL Drawing을 확인해야 하는 실행 명령 전용 조회. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Drawing d where d.eventId = :eventId and d.drawNo = :drawNo")
    Optional<Drawing> findByEventIdAndDrawNoForUpdate(Long eventId, int drawNo);

    boolean existsByEventIdAndDrawNo(Long eventId, int drawNo);

    /** Event 잠금 뒤 같은 Event REDRAW의 실행·Retry 대기 상태를 현재 읽기로 확인한다. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select d
            from Drawing d
            where d.eventId = :eventId
              and d.drawType = kr.co.cking.drawing.domain.DrawingType.REDRAW
              and d.status in :statuses
            """)
    List<Drawing> findAllRedrawByEventIdAndStatusInForUpdate(
            Long eventId,
            Collection<DrawingStatus> statuses
    );

    /** 동시 공개 요청을 직렬화해야 하는 명령 경로(공개) 전용 조회. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Drawing d where d.id = :id")
    Optional<Drawing> findByIdForPublish(Long id);

    /** Retry와 중단 복구가 동일 Drawing을 동시에 실행하지 않도록 쓰기 잠금으로 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Drawing d where d.id = :id")
    Optional<Drawing> findByIdForRetry(Long id);
}
