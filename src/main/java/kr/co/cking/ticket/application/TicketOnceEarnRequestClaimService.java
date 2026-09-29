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
        // REPEATABLE READ에서 먼저 일반 조회하면 이후 INSERT IGNORE 충돌 뒤에도 그
        // 오래된 snapshot을 보게 된다. INSERT IGNORE를 먼저 실행하면 충돌 대기 후의
        // 일반 조회가 최신 커밋 행을 읽고, S-Lock을 X-Lock으로 올릴 필요도 없다.
        return createClaim(command, fingerprint);
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
        // 이 Transaction에서 최초의 일반 조회다. INSERT IGNORE가 충돌한 경우 이미
        // 선행 Transaction의 종료를 기다렸으므로 최신 커밋 행을 읽는다.
        return repository.findByRequestId(command.requestId().toString())
                .map(request -> claimOf(request, fingerprint))
                .orElseGet(() -> repository.findByMemberIdAndCreatorIdAndMissionId(
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
