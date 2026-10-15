package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.CommonOnceEarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/** 영속 선점과 기존 공용 Lua/Stream 실행을 연결한다. */
@Service
@RequiredArgsConstructor
public class CommonTicketOnceEarnServiceImpl implements CommonTicketOnceEarnService {
    private final TicketEarnRequestClaimService globalClaimService;
    private final CommonOnceEarnRequestClaimService onceClaimService;
    private final CommonTicketEarnServiceImpl commonEarnService;

    /** 전역 requestId와 회원·종류 Business Key를 순서대로 선점한 뒤 적립한다. */
    @Override
    public EarnResult earn(CommonOnceEarnCommand command) {
        validate(command);
        TicketEarnRequestClaim global = globalClaimService.claim(command);
        if (global == TicketEarnRequestClaim.REQUEST_ID_CONFLICT) {
            return new EarnResult(EarnResultCode.REQUEST_ID_CONFLICT);
        }
        TicketOnceEarnRequestClaim claim = onceClaimService.claim(command);
        if (claim == TicketOnceEarnRequestClaim.REQUEST_ID_CONFLICT) {
            return new EarnResult(EarnResultCode.REQUEST_ID_CONFLICT);
        }
        if (claim == TicketOnceEarnRequestClaim.DUPLICATE) {
            return new EarnResult(EarnResultCode.DUPLICATE_MISSION);
        }
        if (global == TicketEarnRequestClaim.ACCEPTED || claim == TicketOnceEarnRequestClaim.ACCEPTED) {
            accept(command);
            return new EarnResult(EarnResultCode.ALREADY_PROCESSED);
        }
        if (claim == TicketOnceEarnRequestClaim.EXECUTING) {
            return fromInFlight(command);
        }
        if (!onceClaimService.beginExecution(command.requestId().toString())) {
            return fromInFlight(command);
        }
        EarnResult result = commonEarnService.earnOnceClaimed(command);
        if (result.code() == EarnResultCode.EARN_ACCEPTED || result.code() == EarnResultCode.ALREADY_PROCESSED) {
            accept(command);
        } else if (result.code() == EarnResultCode.BALANCE_MAINTENANCE) {
            onceClaimService.releaseExecution(command.requestId().toString());
        } else if (result.code() == EarnResultCode.EARN_PROCESSING_FAILED) {
            return new EarnResult(EarnResultCode.EARN_STATUS_UNKNOWN);
        }
        return result;
    }

    /** 신규 지급 조건을 다시 판단하기 전에 동일 requestId의 승인 결과를 확인한다. */
    @Override
    public EarnLookupResult findExisting(CommonOnceEarnCommand command) {
        validate(command);
        TicketEarnRequestClaim global = globalClaimService.find(command);
        if (global == TicketEarnRequestClaim.REQUEST_ID_CONFLICT) {
            return new EarnLookupResult(EarnLookupStatus.REQUEST_ID_CONFLICT);
        }
        TicketOnceEarnRequestClaim once = onceClaimService.find(command);
        if (once == TicketOnceEarnRequestClaim.REQUEST_ID_CONFLICT) {
            return new EarnLookupResult(EarnLookupStatus.REQUEST_ID_CONFLICT);
        }
        if (global == TicketEarnRequestClaim.ACCEPTED || once == TicketOnceEarnRequestClaim.ACCEPTED) {
            return new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED);
        }
        if (once == null && global == null) {
            return new EarnLookupResult(EarnLookupStatus.NOT_FOUND);
        }
        EarnLookupResult redis = commonEarnService.findExistingOnceClaimed(command);
        if (redis.status() == EarnLookupStatus.ALREADY_PROCESSED) {
            accept(command);
        }
        if (once == TicketOnceEarnRequestClaim.EXECUTING && redis.status() == EarnLookupStatus.NOT_FOUND) {
            return new EarnLookupResult(EarnLookupStatus.UNAVAILABLE);
        }
        return redis;
    }

    /** 승인된 보상 종류를 Ticket 소유 데이터에서 읽는다. */
    @Override
    public Set<String> findAcceptedRewardTypes(Long memberId) {
        return onceClaimService.findAcceptedRewardTypes(memberId);
    }

    /** Redis 승인 후 두 영속 요청을 별도 짧은 트랜잭션에서 확정한다. */
    private void accept(CommonOnceEarnCommand command) {
        onceClaimService.accept(command.requestId().toString());
        globalClaimService.accept(command.requestId().toString());
    }

    /** 실행권을 이미 발급한 요청은 Redis 승인 증거만 확인하고 불명이면 재실행하지 않는다. */
    private EarnResult fromInFlight(CommonOnceEarnCommand command) {
        EarnLookupResult lookup = commonEarnService.findExistingOnceClaimed(command);
        if (lookup.status() == EarnLookupStatus.ALREADY_PROCESSED) {
            accept(command);
            return new EarnResult(EarnResultCode.ALREADY_PROCESSED);
        }
        return new EarnResult(lookup.status() == EarnLookupStatus.REQUEST_ID_CONFLICT
                ? EarnResultCode.REQUEST_ID_CONFLICT : EarnResultCode.EARN_STATUS_UNKNOWN);
    }

    /** 영속 키와 Redis 키에 사용할 범용 보상 종류 및 적립량을 검증한다. */
    private void validate(CommonOnceEarnCommand command) {
        if (command.requestId() == null || command.userId() == null || command.userId() <= 0
                || command.rewardType() == null || !command.rewardType().matches("[A-Z][A-Z0-9_]{0,49}")
                || command.amount() == null || command.amount() <= 0 || command.requestContext() == null) {
            throw new IllegalArgumentException("공용 ONCE 적립 요청이 올바르지 않습니다.");
        }
    }
}
