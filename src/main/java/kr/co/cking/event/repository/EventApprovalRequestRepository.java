package kr.co.cking.event.repository;

import kr.co.cking.event.domain.EventApprovalRequest;
import kr.co.cking.event.domain.EventApprovalRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Event 승인 요청 이력의 차수와 현재 심사 건을 조회한다. */
public interface EventApprovalRequestRepository extends JpaRepository<EventApprovalRequest, Long> {

    /** Event의 가장 최근 승인 요청 차수를 조회한다. */
    Optional<EventApprovalRequest> findTopByEventIdOrderByApprovalRoundDesc(Long eventId);

    /** Event의 현재 대기 중인 승인 요청을 조회한다. */
    Optional<EventApprovalRequest> findByEventIdAndStatus(Long eventId, EventApprovalRequestStatus status);
}
