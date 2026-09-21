package kr.co.cking.winner.application;

import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.domain.WinnerErrorCode;
import kr.co.cking.winner.domain.WinnerManagement;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import kr.co.cking.winner.repository.WinnerStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 호출자 권한에 따라 Winner 상태 변경 이력을 조회하는 유스케이스다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WinnerStatusHistoryQueryService {

    private final MemberQueryService memberQueryService;
    private final WinnerRepository winnerRepository;
    private final WinnerManagementRepository winnerManagementRepository;
    private final WinnerStatusHistoryRepository winnerStatusHistoryRepository;

    /** Member와 Winner 접근 권한을 검증한 뒤 상태 변경 이력을 시간순으로 반환한다. */
    public List<WinnerStatusHistoryResult> getHistory(Long winnerId, Long userId) {
        MemberRole role = memberQueryService.getRole(userId);
        Winner winner = winnerRepository.findById(winnerId)
                .orElseThrow(() -> new BusinessException(WinnerErrorCode.WINNER_NOT_FOUND));
        validateAccess(role, winner, userId);

        WinnerManagement management = winnerManagementRepository.findByWinnerId(winnerId)
                .orElseThrow(() -> new BusinessException(WinnerErrorCode.WINNER_MANAGEMENT_NOT_FOUND));
        return winnerStatusHistoryRepository
                .findByWinnerManagementIdOrderByCreatedAtAscIdAsc(management.getId())
                .stream()
                .map(WinnerStatusHistoryResult::from)
                .toList();
    }

    /** USER가 본인 소유가 아닌 Winner의 감사 이력을 읽는 것을 차단한다. */
    private void validateAccess(MemberRole role, Winner winner, Long userId) {
        if (role == MemberRole.USER && !winner.getMemberId().equals(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
