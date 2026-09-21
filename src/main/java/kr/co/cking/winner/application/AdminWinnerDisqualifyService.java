package kr.co.cking.winner.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.winner.domain.WinnerErrorCode;
import kr.co.cking.winner.domain.WinnerManagement;
import kr.co.cking.winner.domain.WinnerStatusHistory;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import kr.co.cking.winner.repository.WinnerStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자가 SELECTED Winner를 DISQUALIFIED 종결 상태로 변경하는 유스케이스다. */
@Service
@RequiredArgsConstructor
public class AdminWinnerDisqualifyService {

    private static final int MAX_REASON_LENGTH = 500;

    private final MemberQueryService memberQueryService;
    private final WinnerRepository winnerRepository;
    private final WinnerManagementRepository winnerManagementRepository;
    private final WinnerStatusHistoryRepository winnerStatusHistoryRepository;

    /** 관리자 검증, 자격 박탈 상태 전이, 변경 이력 저장을 하나의 트랜잭션으로 수행한다. */
    @Transactional
    public void disqualify(Long winnerId, Long userId, String reason) {
        memberQueryService.validateAdmin(userId);
        String normalizedReason = normalizeReason(reason);
        validateWinnerExists(winnerId);

        WinnerManagement management = winnerManagementRepository.findByWinnerIdForUpdate(winnerId)
                .orElseThrow(() -> new BusinessException(WinnerErrorCode.WINNER_MANAGEMENT_NOT_FOUND));
        management.disqualify();
        winnerStatusHistoryRepository.save(WinnerStatusHistory.create(
                management.getId(), management.getStatus(), normalizedReason, userId
        ));
    }

    /** 자격 박탈 사유를 감사 가능한 1~500자 값으로 검증하고 저장용으로 정규화한다. */
    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return reason.trim();
    }

    /** 자격 박탈 대상 Winner가 존재하는지 확인해 운영 정보 조회 전 오류를 명확히 한다. */
    private void validateWinnerExists(Long winnerId) {
        if (!winnerRepository.existsById(winnerId)) {
            throw new BusinessException(WinnerErrorCode.WINNER_NOT_FOUND);
        }
    }
}
