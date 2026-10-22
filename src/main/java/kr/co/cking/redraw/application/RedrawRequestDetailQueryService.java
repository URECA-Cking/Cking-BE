package kr.co.cking.redraw.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestVacancy;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import kr.co.cking.redraw.repository.RedrawRequestVacancyRepository;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.repository.WinnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자가 RedrawRequest의 고정 결원과 실행·심사 이력을 조회하도록 조정하는 유스케이스다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RedrawRequestDetailQueryService {

    private final MemberQueryService memberQueryService;
    private final RedrawRequestRepository redrawRequestRepository;
    private final RedrawRequestVacancyRepository redrawRequestVacancyRepository;
    private final WinnerRepository winnerRepository;
    private final DrawingRepository drawingRepository;

    /** 관리자 권한을 검증한 뒤 요청·결원 Winner·실행 Drawing 정보를 상세 응답으로 반환한다. */
    public RedrawRequestDetailResult getDetail(Long redrawRequestId, Long userId) {
        memberQueryService.validateAdmin(userId);

        RedrawRequest request = findRequest(redrawRequestId);
        List<RedrawRequestVacancy> vacancies = redrawRequestVacancyRepository
                .findAllByRedrawRequestIdOrderByIdAsc(redrawRequestId);
        List<RedrawVacancyWinnerResult> vacancyWinners = findVacancyWinners(vacancies);
        Long redrawDrawingId = drawingRepository.findByRedrawRequestId(redrawRequestId)
                .map(drawing -> drawing.getId())
                .orElse(null);

        return RedrawRequestDetailResult.from(request, redrawDrawingId, vacancyWinners);
    }

    /** 식별자로 요청을 조회하고 없으면 Redraw 도메인의 조회 오류를 반환한다. */
    private RedrawRequest findRequest(Long redrawRequestId) {
        return redrawRequestRepository.findById(redrawRequestId)
                .orElseThrow(() -> new BusinessException(RedrawErrorCode.REDRAW_REQUEST_NOT_FOUND));
    }

    /** 고정 결원 순서를 유지하며 Winner와 Member 정보를 관리자 응답 항목으로 변환한다. */
    private List<RedrawVacancyWinnerResult> findVacancyWinners(List<RedrawRequestVacancy> vacancies) {
        List<Long> winnerIds = vacancies.stream().map(RedrawRequestVacancy::getWinnerId).toList();
        Map<Long, Winner> winners = winnerRepository.findAllById(winnerIds).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Winner::getId, Function.identity()));
        Map<Long, MemberInfo> members = memberQueryService.findMemberInfosByIds(
                winners.values().stream().map(Winner::getMemberId).toList()
        );

        return vacancies.stream()
                .map(vacancy -> toVacancyWinner(vacancy.getWinnerId(), winners, members))
                .toList();
    }

    /** 결원 Winner와 Member의 참조 무결성을 확인한 뒤 응답 항목으로 변환한다. */
    private RedrawVacancyWinnerResult toVacancyWinner(
            Long winnerId,
            Map<Long, Winner> winners,
            Map<Long, MemberInfo> members
    ) {
        Winner winner = winners.get(winnerId);
        if (winner == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        MemberInfo member = members.get(winner.getMemberId());
        if (member == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return RedrawVacancyWinnerResult.from(winner, member);
    }
}
