package kr.co.cking.winner.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.domain.WinnerErrorCode;
import kr.co.cking.winner.domain.WinnerManagement;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import kr.co.cking.winner.domain.WinnerStatusHistory;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import kr.co.cking.winner.repository.WinnerStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 당첨자 본인이 자신의 SELECTED Winner를 포기 처리하는 유스케이스다. */
@Service
@RequiredArgsConstructor
public class WinnerDeclineService {

    private final MemberQueryService memberQueryService;
    private final WinnerRepository winnerRepository;
    private final WinnerManagementRepository winnerManagementRepository;
    private final WinnerStatusHistoryRepository winnerStatusHistoryRepository;

    /** 호출자 검증, 상태 전이, 변경 이력 저장을 하나의 트랜잭션으로 수행한다. */
    @Transactional
    public void decline(Long winnerId, Long userId) {
        memberQueryService.validateExists(userId);

        Winner winner = winnerRepository.findById(winnerId)
                .orElseThrow(() -> new BusinessException(WinnerErrorCode.WINNER_NOT_FOUND));
        validateOwnership(winner, userId);

        WinnerManagement management = winnerManagementRepository.findByWinnerIdForUpdate(winnerId)
                .orElseThrow(() -> new BusinessException(WinnerErrorCode.WINNER_MANAGEMENT_NOT_FOUND));
        WinnerManagementStatus previousStatus = management.getStatus();
        management.decline();
        winnerStatusHistoryRepository.save(WinnerStatusHistory.create(
                management.getId(), previousStatus, management.getStatus(), userId
        ));
    }

    /** Winner의 소유자와 호출자가 다르면 다른 사람의 당첨 포기를 차단한다. */
    private void validateOwnership(Winner winner, Long userId) {
        if (!winner.getMemberId().equals(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
