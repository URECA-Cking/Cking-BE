package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.domain.TicketOnceEarnRequest;
import kr.co.cking.ticket.domain.TicketOnceEarnRequestStatus;
import kr.co.cking.ticket.repository.TicketOnceEarnRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** ONCE Redis 실행과 분리된 짧은 DB Transaction으로 durable request를 선점·확정한다. */
@Service
@RequiredArgsConstructor
public class TicketOnceEarnRequestClaimService {

    private final TicketOnceEarnRequestRepository repository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TicketOnceEarnRequestClaim claim(EarnCommand command, String fingerprint) {
        return repository.findByRequestId(command.requestId().toString())
                .map(request -> claimOf(request, fingerprint))
                .orElseGet(() -> createClaim(command, fingerprint));
    }

    @Transactional(readOnly = true)
    public TicketOnceEarnRequestClaim find(String requestId, String fingerprint) {
        return repository.findByRequestId(requestId)
                .map(request -> claimOf(request, fingerprint))
                .orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accept(String requestId) {
        repository.findByRequestId(requestId).ifPresent(request -> {
            if (request.getStatus() == TicketOnceEarnRequestStatus.PENDING) {
                request.accept(clock.instant());
            }
        });
    }

    private TicketOnceEarnRequestClaim createClaim(EarnCommand command, String fingerprint) {
        Instant now = clock.instant();
        int inserted = repository.insertIgnore(
                command.requestId().toString(), command.userId(), command.creatorId(), command.missionId(),
                command.missionType(), command.amount(), command.periodKey(), fingerprint, now);
        if (inserted == 1) {
            return TicketOnceEarnRequestClaim.PENDING;
        }
        // INSERT IGNORE가 0이면 같은 requestId 또는 Business Key를 다른 Transaction이
        // 먼저 선점했다는 뜻이다. 일반 SELECT는 REPEATABLE READ의 이전 snapshot을 볼 수
        // 있으므로, FOR UPDATE 현재 읽기로 상대 Transaction의 커밋 행을 기다려 확인한다.
        return repository.findByRequestIdForUpdate(command.requestId().toString())
                .map(request -> claimOf(request, fingerprint))
                .orElseGet(() -> repository.findByMemberIdAndCreatorIdAndMissionIdForUpdate(
                                command.userId(), command.creatorId(), command.missionId())
                        .map(request -> TicketOnceEarnRequestClaim.DUPLICATE)
                        .orElseThrow(() -> new IllegalStateException("ONCE durable request 충돌 대상을 찾을 수 없습니다.")));
    }

    private TicketOnceEarnRequestClaim claimOf(TicketOnceEarnRequest request, String fingerprint) {
        if (!request.hasSamePayload(fingerprint)) {
            return TicketOnceEarnRequestClaim.REQUEST_ID_CONFLICT;
        }
        return request.getStatus() == TicketOnceEarnRequestStatus.ACCEPTED
                ? TicketOnceEarnRequestClaim.ACCEPTED
                : TicketOnceEarnRequestClaim.PENDING;
    }
}
