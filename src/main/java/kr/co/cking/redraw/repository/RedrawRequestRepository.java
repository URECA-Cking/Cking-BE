package kr.co.cking.redraw.repository;

import java.util.Optional;
import kr.co.cking.redraw.domain.RedrawRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/** RedrawRequest의 멱등 재조회와 영속화를 담당한다. */
public interface RedrawRequestRepository extends JpaRepository<RedrawRequest, Long> {

    /** idempotencyKey로 이전에 생성한 요청을 찾아 재시도 처리에 사용한다. */
    Optional<RedrawRequest> findByIdempotencyKey(String idempotencyKey);

    /** 심사 상태 전이를 직렬화하기 위해 RedrawRequest 행을 비관적 쓰기 잠금으로 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from RedrawRequest request where request.id = :id")
    Optional<RedrawRequest> findByIdForUpdate(@Param("id") Long id);
}
