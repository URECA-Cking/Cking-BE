package kr.co.cking.redraw.application;

import java.util.Map;
import java.util.function.Function;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestStatus;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자가 상태별 RedrawRequest 운영 목록을 조회하도록 권한과 조회 조합을 담당한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RedrawRequestListQueryService {

    private final MemberQueryService memberQueryService;
    private final RedrawRequestRepository redrawRequestRepository;
    private final DrawingRepository drawingRepository;

    /** 관리자 업무 권한을 검증한 뒤 요청·실행 상태 조합에 맞는 목록 페이지를 반환한다. */
    public Page<RedrawRequestListResult> list(
            Long adminId,
            RedrawRequestStatus status,
            RedrawExecutionStatus executionStatus,
            Pageable pageable
    ) {
        memberQueryService.validateAdmin(adminId);
        Page<RedrawRequest> requests = redrawRequestRepository
                .findForAdminList(status, executionStatus, pageable);
        Map<Long, Drawing> drawingsByRequestId = findRedrawDrawings(requests);

        return requests.map(request -> RedrawRequestListResult.from(
                request,
                java.util.Optional.ofNullable(drawingsByRequestId.get(request.getId()))
                        .map(Drawing::getId)
                        .orElse(null)
        ));
    }

    /** 현재 페이지의 요청에 연결된 REDRAW Drawing만 묶음 조회해 N+1 조회를 막는다. */
    private Map<Long, Drawing> findRedrawDrawings(Page<RedrawRequest> requests) {
        if (requests.isEmpty()) {
            return Map.of();
        }
        return drawingRepository.findByRedrawRequestIdIn(requests.stream()
                        .map(RedrawRequest::getId)
                        .toList())
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Drawing::getRedrawRequestId,
                        Function.identity()
                ));
    }
}
