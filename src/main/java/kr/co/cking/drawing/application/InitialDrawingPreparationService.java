package kr.co.cking.drawing.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** INITIAL Drawing 실행 전 관리자·Event·공식 Snapshot 조건을 검증한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InitialDrawingPreparationService {

    private final MemberQueryService memberQueryService;
    private final EventDrawingQueryService eventDrawingQueryService;
    private final SnapshotIntegrityService snapshotIntegrityService;

    public InitialDrawingPreparation prepare(Long adminId, Long eventId) {
        memberQueryService.validateAdmin(adminId);

        EventDrawingSource event = eventDrawingQueryService.getDrawingSource(eventId);
        if (event.deletedAt() != null || event.status() != EventStatus.CLOSED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }

        VerifiedSnapshot snapshot = snapshotIntegrityService.verifyForDrawing(eventId);
        return InitialDrawingPreparation.from(snapshot);
    }
}
