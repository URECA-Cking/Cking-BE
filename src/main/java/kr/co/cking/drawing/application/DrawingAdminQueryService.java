package kr.co.cking.drawing.application;

import java.util.List;
import java.util.Map;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.repository.DrawingRepository;
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

    private final MemberQueryService memberQueryService;
    private final DrawingRepository drawingRepository;
    private final WinnerRepository winnerRepository;

    public DrawingQueryResult getDrawing(Long drawingId, Long userId) {
        memberQueryService.validateAdmin(userId);
        return DrawingQueryResult.from(findDrawing(drawingId));
    }

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

    private Drawing findDrawing(Long drawingId) {
        return drawingRepository.findById(drawingId)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND));
    }

    private MemberInfo findMember(Map<Long, MemberInfo> members, Long memberId) {
        MemberInfo member = members.get(memberId);
        if (member == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return member;
    }
}
