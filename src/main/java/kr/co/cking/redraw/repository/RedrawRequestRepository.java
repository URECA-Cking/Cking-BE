package kr.co.cking.redraw.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** RedrawRequest의 멱등 재조회와 영속화를 담당한다. */
public interface RedrawRequestRepository extends JpaRepository<RedrawRequest, Long> {

    /** idempotencyKey로 이전에 생성한 요청을 찾아 재시도 처리에 사용한다. */
    Optional<RedrawRequest> findByIdempotencyKey(String idempotencyKey);

    /** 관리자 목록에서 요청·실행 상태를 각각 선택적으로 필터링해 최신 요청부터 조회한다. */
    @Query("""
            select request
            from RedrawRequest request
            where (:status is null or request.status = :status)
              and (:executionStatus is null or request.executionStatus = :executionStatus)
            order by request.requestedAt desc, request.id desc
            """)
    Page<RedrawRequest> findForAdminList(
            @Param("status") RedrawRequestStatus status,
            @Param("executionStatus") RedrawExecutionStatus executionStatus,
            Pageable pageable
    );

    /** 심사와 Retry 상태 전이를 직렬화하기 위해 RedrawRequest 행을 비관적 쓰기 잠금으로 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from RedrawRequest request where request.id = :id")
    Optional<RedrawRequest> findByIdForUpdate(@Param("id") Long id);
}
