package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import kr.co.cking.ticket.domain.TicketEarnRequest;
import kr.co.cking.ticket.domain.TicketEarnRequestStatus;
import kr.co.cking.ticket.repository.TicketEarnRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** DAILY·ONCE 공통 requestId를 Redis 실행 전에 선점·확정한다. */
@Service
@RequiredArgsConstructor
public class TicketEarnRequestClaimService {

    private final TicketEarnRequestRepository repository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TicketEarnRequestClaim claim(EarnCommand command) {
        return claim(command.requestId().toString(), command.computeFingerprint(), command.rewardPolicy().name());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TicketEarnRequestClaim claim(CommonEarnCommand command) {
        return claim(command.requestId().toString(), command.computeFingerprint(), "COMMON");
    }

    @Transactional(readOnly = true)
    public TicketEarnRequestClaim find(EarnCommand command) {
        return find(command.requestId().toString(), command.computeFingerprint(), command.rewardPolicy().name());
    }

    @Transactional(readOnly = true)
    public TicketEarnRequestClaim find(CommonEarnCommand command) {
        return find(command.requestId().toString(), command.computeFingerprint(), "COMMON");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accept(String requestId) {
        repository.findByRequestId(requestId).ifPresent(request -> {
            if (request.getStatus() == TicketEarnRequestStatus.PENDING) {
                request.accept(clock.instant());
            }
        });
    }

    private TicketEarnRequestClaim createClaim(String requestId, String fingerprint, String rewardPolicy) {
        Instant now = clock.instant();
        if (repository.insertIgnore(requestId, fingerprint, rewardPolicy, now) == 1) {
            return TicketEarnRequestClaim.PENDING;
        }
        return repository.findByRequestId(requestId)
                .map(request -> claimOf(request, fingerprint, rewardPolicy))
                .orElseThrow(() -> new IllegalStateException("전역 EARN request 충돌 대상을 찾을 수 없습니다."));
    }

    private TicketEarnRequestClaim claim(String requestId, String fingerprint, String rewardPolicy) {
        return repository.findByRequestId(requestId)
                .map(request -> claimOf(request, fingerprint, rewardPolicy))
                .orElseGet(() -> createClaim(requestId, fingerprint, rewardPolicy));
    }

    private TicketEarnRequestClaim find(String requestId, String fingerprint, String rewardPolicy) {
        return repository.findByRequestId(requestId)
                .map(request -> claimOf(request, fingerprint, rewardPolicy))
                .orElse(null);
    }

    private TicketEarnRequestClaim claimOf(TicketEarnRequest request, String fingerprint, String rewardPolicy) {
        if (!request.hasSameRequest(fingerprint, rewardPolicy)) {
            return TicketEarnRequestClaim.REQUEST_ID_CONFLICT;
        }
        return request.getStatus() == TicketEarnRequestStatus.ACCEPTED
                ? TicketEarnRequestClaim.ACCEPTED
                : TicketEarnRequestClaim.PENDING;
    }
}
