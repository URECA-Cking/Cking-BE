package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 수동 마감 요청의 요청자 권한과 시작 가능 상태를 검증한다.
 *
 * <p>Redis Gate 차단과 cutoff 확정은 {@link EventClosingService}에서 먼저 수행하고,
 * 상태 전이는 그 뒤 {@code EventCommandService}가 소유한 짧은 DB 트랜잭션에서 처리한다.
 * 따라서 이 서비스는 트랜잭션을 열지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ManualEventCloseService {

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final EventRepository eventRepository;
    private final EventClosingService eventClosingService;

    /** ADMIN 또는 Event 소유 Creator의 마감 가능 Event 요청만 시스템2로 위임한다. */
    public EventClosingService.ClosingResult close(Long userId, Long eventId) {
        Member member = memberRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Event event = eventRepository.findById(eventId)
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        authorize(member, event);
        if (event.getStatus() != EventStatus.OPEN
                && event.getStatus() != EventStatus.CLOSING
                && event.getStatus() != EventStatus.CLOSED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
        return eventClosingService.startClosing(eventId);
    }

    /** 관리자 전체 권한 또는 Creator의 Event 소유권을 검증한다. */
    private void authorize(Member member, Event event) {
        if (member.getRole() == MemberRole.ADMIN) {
            return;
        }
        Creator creator = creatorRepository.findByMemberId(member.getMemberId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        if (!creator.getCreatorId().equals(event.getCreatorId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
