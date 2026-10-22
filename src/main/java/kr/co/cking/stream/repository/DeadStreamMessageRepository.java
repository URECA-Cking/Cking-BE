package kr.co.cking.stream.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamResolutionStatus;
import kr.co.cking.stream.domain.DeadStreamType;

public interface DeadStreamMessageRepository extends JpaRepository<DeadStreamMessage, Long> {

    Optional<DeadStreamMessage> findBySourceStreamIdAndStreamType(String sourceStreamId, DeadStreamType streamType);

    Page<DeadStreamMessage> findByResolutionStatus(DeadStreamResolutionStatus status, Pageable pageable);

    // 같은 메시지에 대한 동시 replay를 직렬화한다. 상태 검사 전에 행을 잠가야 두 요청이 모두 UNRESOLVED를 읽고
    // 이중으로 적용하거나 먼저 처리한 관리자의 resolvedBy·resolvedAt을 덮어쓰지 않는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from DeadStreamMessage m where m.id = :id")
    Optional<DeadStreamMessage> findByIdForUpdate(@Param("id") Long id);
}
