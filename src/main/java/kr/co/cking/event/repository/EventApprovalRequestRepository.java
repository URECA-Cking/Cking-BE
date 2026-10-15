package kr.co.cking.event.repository;

import kr.co.cking.event.domain.EventApprovalRequest;
import kr.co.cking.event.domain.EventApprovalRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Event 승인 요청 이력의 차수와 현재 심사 건을 조회한다. */
public interface EventApprovalRequestRepository extends JpaRepository<EventApprovalRequest, Long> {

    /** Event의 가장 최근 승인 요청 차수를 조회한다. */
    Optional<EventApprovalRequest> findTopByEventIdOrderByApprovalRoundDesc(Long eventId);

    /** Event의 현재 대기 중인 승인 요청을 조회한다. */
    Optional<EventApprovalRequest> findByEventIdAndStatus(Long eventId, EventApprovalRequestStatus status);

    /** 주어진 Event들의 가장 최근 차수가 REJECTED인 승인 요청을 조회한다. */
    @org.springframework.data.jpa.repository.Query("""
            select r from EventApprovalRequest r
            where r.eventId in :eventIds and r.status = kr.co.cking.event.domain.EventApprovalRequestStatus.REJECTED
              and r.approvalRound = (select max(r2.approvalRound) from EventApprovalRequest r2 where r2.eventId = r.eventId)
            """)
    java.util.List<EventApprovalRequest> findLatestRejected(
            @org.springframework.data.repository.query.Param("eventIds") java.util.Collection<Long> eventIds);

    /** 상태별 승인 요청을 요청 시각과 식별자 오름차순으로 페이지 조회한다. */
    Page<EventApprovalRequest> findByStatusOrderByRequestedAtAscIdAsc(EventApprovalRequestStatus status, Pageable pageable);
}
