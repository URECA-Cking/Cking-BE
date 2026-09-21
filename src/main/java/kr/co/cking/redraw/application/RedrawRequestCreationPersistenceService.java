package kr.co.cking.redraw.application;

import java.util.List;
import java.util.Set;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestVacancy;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import kr.co.cking.redraw.repository.RedrawRequestVacancyRepository;
import kr.co.cking.redraw.repository.RedrawVacancyCandidateRepository;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Event 잠금 안에서 결원을 계산하고 RedrawRequest와 점유 목록을 함께 저장한다. */
@Service
@RequiredArgsConstructor
class RedrawRequestCreationPersistenceService {

    private static final int INITIAL_DRAW_NO = 0;
    private static final List<WinnerManagementStatus> VACANCY_STATUSES = List.of(
            WinnerManagementStatus.DECLINED, WinnerManagementStatus.DISQUALIFIED
    );

    private final EventDrawingQueryService eventDrawingQueryService;
    private final DrawingRepository drawingRepository;
    private final RedrawVacancyCandidateRepository redrawVacancyCandidateRepository;
    private final RedrawRequestRepository redrawRequestRepository;
    private final RedrawRequestVacancyRepository redrawRequestVacancyRepository;

    /** Event 잠금 후 원본 Drawing과 미점유 결원을 확정해 새 RedrawRequest를 저장한다. */
    @Transactional
    public RedrawRequest create(RedrawRequestCreateCommand command) {
        EventDrawingSource event = eventDrawingQueryService.getDrawingSourceForUpdate(command.eventId());
        validatePublishedEvent(event);
        Drawing originalDrawing = findOriginalInitialDrawing(command.eventId());
        List<Long> vacancyWinnerIds = findUnoccupiedVacancyWinnerIds(command.eventId(), originalDrawing.getId());
        if (vacancyWinnerIds.isEmpty()) {
            throw new BusinessException(RedrawErrorCode.NO_REDRAW_VACANCY);
        }

        RedrawRequest request = redrawRequestRepository.saveAndFlush(RedrawRequest.requested(
                command.eventId(), originalDrawing.getId(), vacancyWinnerIds.size(), command.reason(),
                command.idempotencyKey(), command.userId()
        ));
        redrawRequestVacancyRepository.saveAll(vacancyWinnerIds.stream()
                .map(winnerId -> RedrawRequestVacancy.of(request.getId(), winnerId))
                .toList());
        return request;
    }

    /** 삭제되지 않은 PUBLISHED Event에서만 재추첨 요청을 만들도록 검증한다. */
    private void validatePublishedEvent(EventDrawingSource event) {
        if (event.deletedAt() != null || event.status() != EventStatus.PUBLISHED) {
            throw new BusinessException(RedrawErrorCode.INVALID_STATE);
        }
    }

    /** Event의 최초 INITIAL Drawing을 조회하고 유형 불일치 데이터도 명확히 차단한다. */
    private Drawing findOriginalInitialDrawing(Long eventId) {
        Drawing drawing = drawingRepository.findByEventIdAndDrawNo(eventId, INITIAL_DRAW_NO)
                .orElseThrow(() -> new BusinessException(RedrawErrorCode.INVALID_STATE));
        if (drawing.getDrawType() != DrawingType.INITIAL) {
            throw new BusinessException(RedrawErrorCode.INVALID_STATE);
        }
        return drawing;
    }

    /** 결원 후보에서 진행 중 요청이 이미 점유한 Winner를 빼고 생성 대상만 반환한다. */
    private List<Long> findUnoccupiedVacancyWinnerIds(Long eventId, Long originalDrawingId) {
        List<Long> candidateWinnerIds = redrawVacancyCandidateRepository.findVacancyWinnerIds(
                eventId, originalDrawingId, VACANCY_STATUSES
        );
        if (candidateWinnerIds.isEmpty()) {
            return List.of();
        }
        Set<Long> occupiedWinnerIds = Set.copyOf(
                redrawRequestVacancyRepository.findOccupiedWinnerIdsInProgress(candidateWinnerIds)
        );
        return candidateWinnerIds.stream()
                .filter(winnerId -> !occupiedWinnerIds.contains(winnerId))
                .toList();
    }
}
