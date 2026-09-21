package kr.co.cking.winner.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.application.MemberQueryService;
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

/** 관리자가 SELECTED Winner를 RECEIVED 종결 상태로 변경하는 유스케이스다. */
@Service
@RequiredArgsConstructor
public class AdminWinnerReceiveService {

    private final MemberQueryService memberQueryService;
    private final WinnerRepository winnerRepository;
    private final WinnerManagementRepository winnerManagementRepository;
    private final WinnerStatusHistoryRepository winnerStatusHistoryRepository;

    /** 관리자 검증, 수령 완료 상태 전이, 변경 이력 저장을 하나의 트랜잭션으로 수행한다. */
    @Transactional
    public void receive(Long winnerId, Long userId) {
        memberQueryService.validateAdmin(userId);
        validateWinnerExists(winnerId);

        WinnerManagement management = winnerManagementRepository.findByWinnerIdForUpdate(winnerId)
                .orElseThrow(() -> new BusinessException(WinnerErrorCode.WINNER_MANAGEMENT_NOT_FOUND));
        WinnerManagementStatus previousStatus = management.getStatus();
        management.receive();
        winnerStatusHistoryRepository.save(WinnerStatusHistory.create(
                management.getId(), previousStatus, management.getStatus(), userId
        ));
    }

    /** 수령 처리 대상 Winner가 존재하는지 확인해 운영 정보 조회 전 오류를 명확히 한다. */
    private void validateWinnerExists(Long winnerId) {
        if (!winnerRepository.existsById(winnerId)) {
            throw new BusinessException(WinnerErrorCode.WINNER_NOT_FOUND);
        }
    }
}
