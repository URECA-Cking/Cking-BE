package kr.co.cking.ticket.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import kr.co.cking.mission.CommonMissionCompletion;
import kr.co.cking.mission.CommonMissionCompletionRepository;
import kr.co.cking.mission.CommonMissionRepository;
import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import kr.co.cking.ticket.domain.CommonTicketLedger;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.repository.CommonTicketLedgerRepository;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link TicketEarnLedgerService}와 동일 계약의 공용 EARN Stream Consumer DB 반영
 * 로직(이슈 #219). {@code common_mission_completion} INSERT → 공용 EARN Ledger
 * INSERT → 공용 Balance UPDATE를 한 트랜잭션으로 묶는다. 반영에 실패한 메시지는
 * {@code CommonEarnStreamPelRecoveryScheduler}가 회수해 재처리한다.
 *
 * <p>알려진 제약(미해결): 공용 EARN은 Dead Stream 이관·replay를 아직 지원하지 않는다.
 * 최대 재시도를 넘긴 메시지는 PEL에 보존된 채 더 긴 간격으로 계속 재처리된다 — 후속 이슈.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommonMissionEarnLedgerService {

    private final CommonMissionRepository commonMissionRepository;
    private final CommonMissionCompletionRepository commonMissionCompletionRepository;
    private final CommonTicketLedgerRepository commonTicketLedgerRepository;
    private final UserCommonTicketBalanceRepository userCommonTicketBalanceRepository;

    @Transactional
    public void apply(CommonEarnCommand command) {
        String requestId = command.requestId().toString();
        String fingerprint = computeFingerprint(command);

        Optional<CommonMissionCompletion> existing = commonMissionCompletionRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            verifySameRequest(existing.get(), fingerprint, requestId);
            log.info("이미 반영된 공용 EARN 요청이라 재적립하지 않습니다. requestId={}", requestId);
            return;
        }

        if (!commonMissionRepository.findByMissionId(command.missionId()).isPresent()) {
            throw new IllegalStateException(
                    "존재하지 않는 공용 missionId입니다. missionId=%d, requestId=%s"
                            .formatted(command.missionId(), requestId));
        }

        Instant now = Instant.now();

        // uk_common_completion_request 충돌(진짜 동시 재전달 경합)이 나면 여기서 복구를
        // 시도하지 않고 그대로 던져 트랜잭션을 롤백한다 — TicketEarnLedgerService와
        // 동일 원칙: 재전달된 메시지가 새 트랜잭션에서 findByRequestId로 정상 처리된다.
        CommonMissionCompletion completion = commonMissionCompletionRepository.save(
                CommonMissionCompletion.builder()
                        .memberId(command.userId())
                        .missionId(command.missionId())
                        .periodKey(command.periodKey())
                        .requestId(requestId)
                        .payloadFingerprint(fingerprint)
                        .completedAt(now)
                        .build()
        );

        UserCommonTicketBalance balance = userCommonTicketBalanceRepository
                .findByMemberIdForUpdate(command.userId())
                .orElseGet(() -> userCommonTicketBalanceRepository.save(
                        UserCommonTicketBalance.builder()
                                .memberId(command.userId())
                                .balance(0L)
                                .updatedAt(now)
                                .build()
                ));

        long balanceBefore = balance.getBalance();
        balance.applyDelta(command.amount(), now);

        commonTicketLedgerRepository.save(
                CommonTicketLedger.builder()
                        .memberId(command.userId())
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

    private void verifySameRequest(CommonMissionCompletion existing, String fingerprint, String requestId) {
        if (!existing.getPayloadFingerprint().equals(fingerprint)) {
            throw new IllegalStateException(
                    "동일 requestId에 다른 요청 내용이 감지됐습니다. requestId=%s".formatted(requestId));
        }
    }

    private String computeFingerprint(CommonEarnCommand command) {
        String payload = String.join(":",
                String.valueOf(command.userId()),
                command.missionType(),
                String.valueOf(command.missionId()),
                command.periodKey(),
                String.valueOf(command.amount()));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
