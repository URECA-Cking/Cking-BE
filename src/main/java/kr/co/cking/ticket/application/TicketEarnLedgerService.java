package kr.co.cking.ticket.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

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
        String fingerprint = computeFingerprint(command);

        Optional<MissionCompletion> existing = missionCompletionRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            verifySameRequest(existing.get(), command, fingerprint);
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
                        .payloadFingerprint(fingerprint)
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
    // 필드 하나하나를 비교하면 새 필드가 추가될 때마다 빠뜨리기 쉬우므로(리뷰에서
    // missionType·missionKey 누락이 지적됨), 저장해 둔 전체 payload fingerprint와
    // 비교한다 — SPEND 쪽 EntrySpendServiceImpl과 동일한 패턴.
    private void verifySameRequest(MissionCompletion existing, EarnCommand command, String fingerprint) {
        if (!existing.getPayloadFingerprint().equals(fingerprint)) {
            throw new IllegalStateException(
                    "동일 requestId에 다른 요청 내용이 감지됐습니다. requestId=%s"
                            .formatted(command.requestId()));
        }
    }

    // EntrySpendServiceImpl.computeFingerprint와 동일한 패턴. requestId는 상관관계
    // 키일 뿐 내용이 아니므로 fingerprint 계산에서 제외한다.
    private String computeFingerprint(EarnCommand command) {
        String payload = String.join(":",
                String.valueOf(command.userId()),
                String.valueOf(command.creatorId()),
                command.missionType(),
                String.valueOf(command.missionId()),
                command.periodKey(),
                command.missionKey(),
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
