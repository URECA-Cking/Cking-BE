package kr.co.cking.drawing.repository;

import jakarta.persistence.LockModeType;
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

    /**
     * Event 행 잠금 뒤 최신 상태를 현재 읽기로 확인한다. 일반 일관 읽기는 준비 Transaction 시작 시점의 Snapshot을
     * 볼 수 있어, 다른 REDRAW가 방금 `RUNNING`으로 전이한 사실을 놓칠 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select d from Drawing d where d.eventId = :eventId and d.status = :status")
    List<Drawing> findAllByEventIdAndStatusForUpdate(Long eventId, DrawingStatus status);

    /** 동시 공개 요청을 직렬화해야 하는 명령 경로(공개) 전용 조회. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Drawing d where d.id = :id")
    Optional<Drawing> findByIdForPublish(Long id);

    /** Retry와 중단 복구가 동일 Drawing을 동시에 실행하지 않도록 쓰기 잠금으로 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Drawing d where d.id = :id")
    Optional<Drawing> findByIdForRetry(Long id);
}
