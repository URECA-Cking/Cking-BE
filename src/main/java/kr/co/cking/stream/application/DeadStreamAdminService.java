package kr.co.cking.stream.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamResolutionStatus;
import kr.co.cking.stream.repository.DeadStreamMessageRepository;
import lombok.RequiredArgsConstructor;

/** 운영자가 Dead Stream 메시지를 조회하고 수동 replay하도록 ADMIN 권한을 검증한다. */
@Service
@RequiredArgsConstructor
public class DeadStreamAdminService {

    private final MemberQueryService memberQueryService;
    private final DeadStreamMessageRepository deadStreamMessageRepository;
    private final DeadStreamReplayService deadStreamReplayService;

    /** 처리 대기 큐처럼 오래된 메시지부터 반환한다(API 명세 §1.7 관리자 목록 정렬, PK로 tie-break). */
    @Transactional(readOnly = true)
    public Page<DeadStreamMessage> list(Long adminId, DeadStreamResolutionStatus status, int page, int size) {
        memberQueryService.validateAdmin(adminId);
        return deadStreamMessageRepository.findByResolutionStatus(
                status, PageRequest.of(page, size, Sort.by("createdAt", "id")));
    }

    public DeadStreamMessage replay(Long adminId, Long deadStreamMessageId) {
        memberQueryService.validateAdmin(adminId);
        return deadStreamReplayService.replay(deadStreamMessageId, adminId);
    }
}
