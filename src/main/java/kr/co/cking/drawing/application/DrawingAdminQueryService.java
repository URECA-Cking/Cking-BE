package kr.co.cking.drawing.application;

import java.util.List;
import java.util.Map;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.repository.WinnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DrawingAdminQueryService {

    private static final int INITIAL_DRAW_NO = 0;

    private final MemberQueryService memberQueryService;
    private final DrawingRepository drawingRepository;
    private final WinnerRepository winnerRepository;
    private final EventDrawingQueryService eventDrawingQueryService;

    /** 관리자 권한을 확인한 뒤 Drawing ID로 상세 실행 정보를 조회한다. */
    public DrawingQueryResult getDrawing(Long drawingId, Long userId) {
        memberQueryService.validateAdmin(userId);
        return DrawingQueryResult.from(findDrawing(drawingId));
    }

    /** 관리자 권한을 확인한 뒤 Event의 INITIAL Drawing 상세 실행 정보를 조회한다. */
    public DrawingQueryResult getInitialDrawing(Long eventId, Long userId) {
        memberQueryService.validateAdmin(userId);
        eventDrawingQueryService.getDrawingSource(eventId);
        return DrawingQueryResult.from(findInitialDrawing(eventId));
    }

    /** 관리자 권한을 확인한 뒤 완료된 Drawing의 당첨 결과를 순위와 회원 정보로 조회한다. */
    public DrawingResultQuery getDrawingResult(Long drawingId, Long userId) {
        memberQueryService.validateAdmin(userId);

        Drawing drawing = findDrawing(drawingId);
        if (drawing.getStatus() != DrawingStatus.COMPLETED) {
            throw new BusinessException(DrawingErrorCode.DRAWING_NOT_COMPLETED);
        }

        List<Winner> winners = winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(drawingId);
        Map<Long, MemberInfo> members = memberQueryService.findMemberInfosByIds(
                winners.stream().map(Winner::getMemberId).toList()
        );
        List<DrawingWinnerResult> results = winners.stream()
                .map(winner -> DrawingWinnerResult.from(winner, findMember(members, winner.getMemberId())))
                .toList();

        return new DrawingResultQuery(drawingId, results);
    }

    /** Drawing ID에 해당하는 Drawing을 조회하고 없으면 Drawing 전용 오류로 변환한다. */
    private Drawing findDrawing(Long drawingId) {
        return drawingRepository.findById(drawingId)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND));
    }

    /** Event의 0회차 Drawing이 INITIAL 유형인지 확인해 최초 추첨만 반환한다. */
    private Drawing findInitialDrawing(Long eventId) {
        Drawing drawing = drawingRepository.findByEventIdAndDrawNo(eventId, INITIAL_DRAW_NO)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND));
        if (drawing.getDrawType() != kr.co.cking.drawing.domain.DrawingType.INITIAL) {
            throw new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND);
        }
        return drawing;
    }

    /** Winner가 참조하는 Member 정보가 조회 결과에 있는지 확인해 반환한다. */
    private MemberInfo findMember(Map<Long, MemberInfo> members, Long memberId) {
        MemberInfo member = members.get(memberId);
        if (member == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return member;
    }
}
