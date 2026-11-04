package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.ticket.application.TicketEarnService;
import kr.co.cking.ticket.application.TicketOnceEarnService;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Creator 보상의 신규 Redis 수락을 자격 상태 변경과 직렬화한다. */
@Service
@RequiredArgsConstructor
public class CreatorRewardAdmissionService {
    private final CreatorRepository creators;
    private final CreatorApplicationLockManager lockManager;
    private final TicketEarnService dailyEarn;
    private final TicketOnceEarnService onceEarn;

    public EarnResult earnDaily(EarnCommand command) {
        if (dailyEarn.findExisting(command).status() == EarnLookupStatus.ALREADY_PROCESSED) {
            return new EarnResult(EarnResultCode.ALREADY_PROCESSED);
        }
        return lockManager.executeWithoutTransaction(lockKey(command.creatorId()), 15, () -> {
            if (dailyEarn.findExisting(command).status() == EarnLookupStatus.ALREADY_PROCESSED) {
                return new EarnResult(EarnResultCode.ALREADY_PROCESSED);
            }
            requireActive(command.creatorId());
            return dailyEarn.earn(command);
        });
    }

    public EarnResult earnOnce(EarnCommand command) {
        if (onceEarn.findExisting(command).status() == EarnLookupStatus.ALREADY_PROCESSED) {
            return new EarnResult(EarnResultCode.ALREADY_PROCESSED);
        }
        return lockManager.executeWithoutTransaction(lockKey(command.creatorId()), 15, () -> {
            if (onceEarn.findExisting(command).status() == EarnLookupStatus.ALREADY_PROCESSED) {
                return new EarnResult(EarnResultCode.ALREADY_PROCESSED);
            }
            requireActive(command.creatorId());
            return onceEarn.earn(command);
        });
    }

    static String lockKey(Long creatorId) {
        return "creator-reward-admission:" + creatorId;
    }

    private void requireActive(Long creatorId) {
        creators.findById(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .requireActive();
    }
}
