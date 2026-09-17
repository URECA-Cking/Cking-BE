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
import org.springframework.transaction.annotation.Transactional;

/** 수동 마감 요청의 요청자 권한과 시작 가능 상태를 검증한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class ManualEventCloseService {

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final EventRepository eventRepository;
    private final EventClosingService eventClosingService;

    /** ADMIN 또는 Event 소유 Creator의 OPEN Event 마감 요청만 시스템2로 위임한다. */
    public EventClosingService.ClosingResult close(Long userId, Long eventId) {
        Member member = memberRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Event event = eventRepository.findById(eventId)
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        authorize(member, event);
        if (event.getStatus() != EventStatus.OPEN) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
        return eventClosingService.startClosing(eventId);
    }

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
