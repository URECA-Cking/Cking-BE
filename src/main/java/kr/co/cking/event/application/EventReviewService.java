package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventApprovalRequest;
import kr.co.cking.event.domain.EventApprovalRequestStatus;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.repository.EventApprovalRequestRepository;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.domain.Creator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 관리자의 Event 승인·거절과 승인 요청 이력 기록을 처리한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class EventReviewService {

    private final MemberRepository memberRepository;
    private final EventRepository eventRepository;
    private final EventApprovalRequestRepository approvalRequestRepository;
    private final EventCommandService eventCommandService;
    private final CreatorRepository creatorRepository;

    /** 만료되지 않은 승인 대기 Event와 현재 요청을 승인 처리한다. */
    public EventApprovalRequest approve(Long adminId, Long eventId) {
        requireAdmin(adminId);
        Event event = findLockedEvent(eventId);
        if (!event.getEndAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
        EventApprovalRequest request = findPendingRequest(eventId);
        request.approve(adminId);
        eventCommandService.approve(eventId);
        return request;
    }

    /** 관리자가 심사할 수 있는 대기 중 승인 요청 목록을 페이지로 조회한다. */
    @Transactional(readOnly = true)
    public Page<PendingEvent> findPending(Long adminId, Pageable pageable) {
        requireAdmin(adminId);
        return approvalRequestRepository.findByStatusOrderByRequestedAtAscIdAsc(EventApprovalRequestStatus.PENDING, pageable)
                .map(this::toPendingEvent);
    }

    /** 승인 요청과 연결된 Event·Creator 정보를 관리자 목록 항목으로 결합한다. */
    private PendingEvent toPendingEvent(EventApprovalRequest request) {
        Event event = eventRepository.findById(request.getEventId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Creator creator = creatorRepository.findById(event.getCreatorId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return new PendingEvent(request, event, creator.getName());
    }

    /** 관리자 승인 대기 목록에 필요한 승인 요청·Event·Creator 정보를 전달한다. */
    public record PendingEvent(EventApprovalRequest request, Event event, String creatorName) {
    }

    /** 승인 대기 Event를 거절하고 현재 승인 요청 이력에 사유를 기록한다. */
    public EventApprovalRequest reject(Long adminId, Long eventId, String rejectReason) {
        requireAdmin(adminId);
        if (rejectReason == null || rejectReason.isBlank()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        findLockedEvent(eventId);
        EventApprovalRequest request = findPendingRequest(eventId);
        request.reject(adminId, rejectReason.trim());
        eventCommandService.reject(eventId, rejectReason.trim());
        return request;
    }

    /** 관리자 Member인지 검증한다. */
    private void requireAdmin(Long adminId) {
        Member admin = memberRepository.findById(adminId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (admin.getRole() != MemberRole.ADMIN) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    /** 상충 심사 명령을 막기 위해 Event 행을 잠금 상태로 조회한다. */
    private Event findLockedEvent(Long eventId) {
        return eventRepository.findByEventId(eventId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 현재 Event에 연결된 대기 중 승인 요청을 조회한다. */
    private EventApprovalRequest findPendingRequest(Long eventId) {
        return approvalRequestRepository.findByEventIdAndStatus(eventId, EventApprovalRequestStatus.PENDING)
                .orElseThrow(() -> new BusinessException(EventErrorCode.INVALID_STATE));
    }
}
