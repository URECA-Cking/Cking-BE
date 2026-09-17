package kr.co.cking.ticket.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionCompletion;
import kr.co.cking.mission.MissionCompletionRepository;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.domain.TicketLedger;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.TicketLedgerRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * EARN Stream Consumer가 호출하는 DB 반영 로직. MissionCompletion INSERT → EARN
 * Ledger INSERT → Balance UPDATE를 한 트랜잭션으로 묶는다(통합 API 명세 v2.5 §9.3).
 * 호출자(Consumer)는 이 메서드가 예외 없이 반환한 뒤에만 XACK한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketEarnLedgerService {

    private final MissionRepository missionRepository;
    private final MissionCompletionRepository missionCompletionRepository;
    private final TicketLedgerRepository ticketLedgerRepository;
    private final UserTicketBalanceRepository userTicketBalanceRepository;

    @Transactional
    public void apply(EarnCommand command) {
        String requestId = command.requestId().toString();

        Optional<MissionCompletion> existing = missionCompletionRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            verifySameRequest(existing.get(), command);
            log.info("이미 반영된 EARN 요청이라 재적립하지 않습니다. requestId={}", requestId);
            return;
        }

        if (!missionRepository.existsByMissionIdAndCreatorId(command.missionId(), command.creatorId())) {
            throw new IllegalStateException(
                    "missionId가 creatorId 소속이 아닙니다. missionId=%d, creatorId=%d, requestId=%s"
                            .formatted(command.missionId(), command.creatorId(), requestId));
        }

        Instant now = Instant.now();

        // uk_completion_request 충돌(진짜 동시 재전달 경합)이 나면 여기서 복구를 시도하지
        // 않고 그대로 던져서 트랜잭션을 롤백한다 — INSERT 실패로 flush된 세션은 이미
        // 오염된 상태라 같은 트랜잭션 안에서 재조회하면 Hibernate가
        // AssertionFailure(null identifier)를 던진다. 호출자(Consumer)가 XACK하지 않고
        // 재전달하면, 그때는 새 트랜잭션·새 세션에서 위 findByRequestId 확인이 그 사이
        // 커밋된 행을 정상적으로 찾아 멱등 처리한다.
        MissionCompletion completion = missionCompletionRepository.save(
                MissionCompletion.builder()
                        .memberId(command.userId())
                        .creatorId(command.creatorId())
                        .missionId(command.missionId())
                        .periodKey(command.periodKey())
                        .requestId(requestId)
                        .completedAt(now)
                        .build()
        );

        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorIdForUpdate(command.userId(), command.creatorId())
                .orElseGet(() -> userTicketBalanceRepository.save(
                        UserTicketBalance.builder()
                                .memberId(command.userId())
                                .creatorId(command.creatorId())
                                .balance(0L)
                                .updatedAt(now)
                                .build()
                ));

        long balanceBefore = balance.getBalance();
        balance.applyDelta(command.amount(), now);

        ticketLedgerRepository.save(
                TicketLedger.builder()
                        .memberId(command.userId())
                        .creatorId(command.creatorId())
                        .missionCompletionId(completion.getCompletionId())
                        .deltaAmount(command.amount())
                        .type(TicketLedgerType.EARN)
                        .requestId(requestId)
                        .balanceBefore(balanceBefore)
                        .balanceAfter(balance.getBalance())
                        .createdAt(now)
                        .build()
        );
    }

    // 같은 requestId라도 payload가 다르면 다른 요청이다 — 존재 여부만으로 멱등 재처리를
    // 판단하지 않고, 기존 데이터와 내용까지 일치하는 경우에만 정상 재전달로 인정한다.
    private void verifySameRequest(MissionCompletion existing, EarnCommand command) {
        boolean sameCompletion = existing.getMemberId().equals(command.userId())
                && existing.getCreatorId().equals(command.creatorId())
                && existing.getMissionId().equals(command.missionId())
                && existing.getPeriodKey().equals(command.periodKey());

        if (!sameCompletion) {
            throw new IllegalStateException(
                    "동일 requestId에 다른 요청 내용이 감지됐습니다. requestId=%s, 기존=(memberId=%d, creatorId=%d, missionId=%d, periodKey=%s), 신규=(memberId=%d, creatorId=%d, missionId=%d, periodKey=%s)"
                            .formatted(command.requestId(),
                                    existing.getMemberId(), existing.getCreatorId(),
                                    existing.getMissionId(), existing.getPeriodKey(),
                                    command.userId(), command.creatorId(),
                                    command.missionId(), command.periodKey()));
        }

        Long existingAmount = ticketLedgerRepository.findByRequestId(command.requestId().toString())
                .map(TicketLedger::getDeltaAmount)
                .orElse(null);

        if (existingAmount != null && !existingAmount.equals(command.amount())) {
            throw new IllegalStateException(
                    "동일 requestId에 다른 amount가 감지됐습니다. requestId=%s, 기존amount=%d, 신규amount=%d"
                            .formatted(command.requestId(), existingAmount, command.amount()));
        }
    }
}
