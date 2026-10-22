package kr.co.cking.event.application;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import kr.co.cking.snapshot.repository.DrawSnapshotRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자가 Event 한 건의 기본 정보와 마감·Snapshot·INITIAL Drawing 운영 요약을 함께 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminEventOperationQueryService {

    private static final int INITIAL_DRAW_NO = 0;

    private final MemberQueryService memberQueryService;
    private final EventRepository eventRepository;
    private final CreatorRepository creatorRepository;
    private final DrawSnapshotRepository snapshotRepository;
    private final DrawingRepository drawingRepository;
    private final WinnerRepository winnerRepository;

    /** 관리자 권한을 검증한 뒤 Event 기본 정보와 운영 단계별 존재·상태만 반환한다. */
    public AdminEventOperationResult getOperation(Long adminId, Long eventId) {
        memberQueryService.validateAdmin(adminId);

        Event event = eventRepository.findByEventIdAndDeletedAtIsNull(eventId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Creator creator = creatorRepository.findById(event.getCreatorId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        Optional<DrawSnapshot> snapshot = snapshotRepository.findByEventId(eventId);
        Optional<Drawing> initialDrawing = drawingRepository.findByEventIdAndDrawNo(eventId, INITIAL_DRAW_NO)
                .filter(drawing -> drawing.getDrawType() == DrawingType.INITIAL);

        return AdminEventOperationResult.of(
                event,
                creator.publicDisplayName(),
                snapshot.map(AdminEventOperationResult.SnapshotSummary::from).orElse(null),
                initialDrawing.map(drawing -> AdminEventOperationResult.DrawingSummary.from(
                        drawing, winnerRepository.countByDrawingId(drawing.getId())))
                        .orElse(null)
        );
    }
}
