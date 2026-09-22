package kr.co.cking.redraw.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import kr.co.cking.redraw.domain.RedrawRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** RedrawRequest의 멱등 재조회와 영속화를 담당한다. */
public interface RedrawRequestRepository extends JpaRepository<RedrawRequest, Long> {

    /** idempotencyKey로 이전에 생성한 요청을 찾아 재시도 처리에 사용한다. */
    Optional<RedrawRequest> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RedrawRequest r where r.id = :id")
    Optional<RedrawRequest> findByIdForUpdate(Long id);
}
