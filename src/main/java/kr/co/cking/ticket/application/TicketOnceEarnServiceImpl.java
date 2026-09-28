package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.application.dto.EarnRewardPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Redis 유실 뒤에도 DB Business Key가 재적립을 막도록 ONCE request를 먼저 claim한다. */
@Service
@RequiredArgsConstructor
public class TicketOnceEarnServiceImpl implements TicketOnceEarnService {

    private final TicketOnceEarnRequestClaimService claimService;
    private final TicketEarnRequestClaimService requestClaimService;
    private final TicketEarnService ticketEarnService;

    @Override
    public EarnResult earn(EarnCommand command) {
        validateOnce(command);
        EarnResult globalClaimResult = globalClaimResult(command);
        if (globalClaimResult != null) {
            return globalClaimResult;
        }

        TicketOnceEarnRequestClaim claim = claimService.claim(command, command.computeFingerprint());
        if (claim == TicketOnceEarnRequestClaim.REQUEST_ID_CONFLICT) {
            return new EarnResult(EarnResultCode.REQUEST_ID_CONFLICT);
        }
        if (claim == TicketOnceEarnRequestClaim.DUPLICATE) {
            return new EarnResult(EarnResultCode.DUPLICATE_MISSION);
        }
        if (claim == TicketOnceEarnRequestClaim.ACCEPTED) {
            return new EarnResult(EarnResultCode.ALREADY_PROCESSED);
        }

        EarnResult result = ticketEarnService.earn(command);
        if (result.code() == EarnResultCode.EARN_ACCEPTED || result.code() == EarnResultCode.ALREADY_PROCESSED) {
            claimService.accept(command.requestId().toString());
        }
        return result;
    }

    @Override
    public EarnLookupResult findExisting(EarnCommand command) {
        validateOnce(command);
        EarnLookupResult globalClaimResult = findGlobalClaimResult(command);
        if (globalClaimResult != null) {
            return globalClaimResult;
        }

        TicketOnceEarnRequestClaim claim = claimService.find(command.requestId().toString(), command.computeFingerprint());
        if (claim == null) {
            return new EarnLookupResult(EarnLookupStatus.NOT_FOUND);
        }
        if (claim == TicketOnceEarnRequestClaim.REQUEST_ID_CONFLICT) {
            return new EarnLookupResult(EarnLookupStatus.REQUEST_ID_CONFLICT);
        }
        if (claim == TicketOnceEarnRequestClaim.ACCEPTED) {
            return new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED);
        }
        EarnLookupResult redisResult = ticketEarnService.findExisting(command);
        if (redisResult.status() == EarnLookupStatus.ALREADY_PROCESSED) {
            claimService.accept(command.requestId().toString());
        }
        return redisResult;
    }

    private void validateOnce(EarnCommand command) {
        if (command.rewardPolicy() != EarnRewardPolicy.ONCE) {
            throw new IllegalArgumentException("TicketOnceEarnService에는 ONCE 정책만 전달할 수 있습니다.");
        }
    }

    private EarnResult globalClaimResult(EarnCommand command) {
        return switch (requestClaimService.claim(command)) {
            case PENDING -> null;
            case ACCEPTED -> new EarnResult(EarnResultCode.ALREADY_PROCESSED);
            case REQUEST_ID_CONFLICT -> new EarnResult(EarnResultCode.REQUEST_ID_CONFLICT);
        };
    }

    private EarnLookupResult findGlobalClaimResult(EarnCommand command) {
        TicketEarnRequestClaim claim = requestClaimService.find(command);
        if (claim == null || claim == TicketEarnRequestClaim.PENDING) {
            return null;
        }
        return claim == TicketEarnRequestClaim.ACCEPTED
                ? new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED)
                : new EarnLookupResult(EarnLookupStatus.REQUEST_ID_CONFLICT);
    }

}
