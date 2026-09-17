package kr.co.cking.drawing.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import kr.co.cking.drawing.domain.Drawing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DrawingRepository extends JpaRepository<Drawing, Long> {

    Optional<Drawing> findByEventIdAndDrawNo(Long eventId, int drawNo);

    boolean existsByEventIdAndDrawNo(Long eventId, int drawNo);

    /** 동시 공개 요청을 직렬화해야 하는 명령 경로(공개) 전용 조회. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Drawing d where d.id = :id")
    Optional<Drawing> findByIdForPublish(Long id);
}
