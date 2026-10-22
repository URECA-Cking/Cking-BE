package kr.co.cking.event.application;

import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자가 마감 진행 상태를 조회할 수 있도록 권한을 검증하고 시스템2 조회를 위임한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminClosingStatusQueryService {

    private final MemberQueryService memberQueryService;
    private final EventClosingService eventClosingService;

    /** 관리자 Member를 검증한 뒤 시스템2에서 Event의 현재 마감 상태를 조회한다. */
    public EventStatus getClosingStatus(Long userId, Long eventId) {
        memberQueryService.validateAdmin(userId);
        return eventClosingService.getClosingStatus(eventId);
    }
}
