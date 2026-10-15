package kr.co.cking.ticket.application;

import kr.co.cking.ticket.domain.CommonTicketLedger;
import kr.co.cking.ticket.domain.TicketCommonOnceEarnRequest;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.TicketOnceEarnRequestStatus;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.repository.CommonTicketLedgerRepository;
import kr.co.cking.ticket.repository.TicketCommonOnceEarnRequestRepository;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** 기존 공용 EARN Consumer 안에서 평생 1회 지급의 원장·DB 잔액을 반영한다. */
@Service
@RequiredArgsConstructor
public class CommonOnceEarnLedgerService {
    private final TicketCommonOnceEarnRequestRepository requestRepository;
    private final CommonTicketLedgerRepository ledgerRepository;
    private final UserCommonTicketBalanceRepository balanceRepository;
    private final Clock clock;

    /** 승인 Stream의 원인과 내용을 대조하고 원장·잔액을 한 트랜잭션에 기록한다. */
    @Transactional
    public void apply(Map<String, String> fields) {
        String requestId = fields.get("requestId");
        TicketCommonOnceEarnRequest request = requestRepository.findByRequestId(requestId)
                .orElseThrow(() -> new IllegalStateException("공용 ONCE 지급 원인이 없습니다: " + requestId));
        if (!String.valueOf(request.getMemberId()).equals(fields.get("userId"))
                || !request.getRewardType().equals(fields.get("rewardType"))
                || !String.valueOf(request.getAmount()).equals(fields.get("amount"))
                || !request.getPayloadFingerprint().equals(fields.get("fingerprint"))) {
            throw new IllegalStateException("공용 ONCE Stream 내용이 영속 지급 원인과 다릅니다: " + requestId);
        }
        var existingLedger = ledgerRepository.findByRequestId(requestId);
        if (existingLedger.isPresent()) {
            if (!request.getOnceEarnRequestId().equals(existingLedger.get().getOnceEarnRequestId())) {
                throw new IllegalStateException("공용 ONCE Stream requestId가 다른 원장 원인과 충돌합니다: " + requestId);
            }
            return;
        }
        Instant now = clock.instant();
        UserCommonTicketBalance balance = balanceRepository.findByMemberIdForUpdate(request.getMemberId())
                .orElseGet(() -> balanceRepository.save(UserCommonTicketBalance.builder()
                        .memberId(request.getMemberId()).balance(0L).updatedAt(now).build()));
        long before = balance.getBalance();
        balance.applyDelta(request.getAmount(), now);
        ledgerRepository.save(CommonTicketLedger.builder()
                .memberId(request.getMemberId())
                .onceEarnRequestId(request.getOnceEarnRequestId())
                .deltaAmount(request.getAmount())
                .type(TicketLedgerType.EARN)
                .requestId(requestId)
                .reason(request.getRewardType())
                .balanceBefore(before)
                .balanceAfter(balance.getBalance())
                .createdAt(now)
                .build());
        if (request.getStatus() != TicketOnceEarnRequestStatus.ACCEPTED) {
            request.accept(now);
        }
    }
}
