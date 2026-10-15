package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.CommonOnceEarnCommand;
import kr.co.cking.ticket.domain.TicketCommonOnceEarnRequest;
import kr.co.cking.ticket.domain.TicketOnceEarnRequestStatus;
import kr.co.cking.ticket.repository.TicketCommonOnceEarnRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;

/** 공용 평생 1회 Business Key를 Redis 실행과 분리된 트랜잭션에서 선점한다. */
@Service
@RequiredArgsConstructor
public class CommonOnceEarnRequestClaimService {
    private final TicketCommonOnceEarnRequestRepository repository;
    private final Clock clock;

    /** 회원·종류 UNIQUE를 먼저 INSERT해 동시 요청을 직렬화하고 충돌 원인을 판별한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TicketOnceEarnRequestClaim claim(CommonOnceEarnCommand command) {
        int inserted = repository.insertIgnore(command.requestId().toString(), command.userId(),
                command.rewardType(), command.amount(), command.computeFingerprint(), clock.instant());
        if (inserted == 1) {
            return TicketOnceEarnRequestClaim.PENDING;
        }
        return repository.findByRequestId(command.requestId().toString())
                .map(request -> claimOf(request, command.computeFingerprint()))
                .orElseGet(() -> repository.findByMemberIdAndRewardType(command.userId(), command.rewardType())
                        .map(request -> TicketOnceEarnRequestClaim.DUPLICATE)
                        .orElseThrow(() -> new IllegalStateException("공용 ONCE 선점 충돌 대상을 찾을 수 없습니다.")));
    }

    /** 기존 requestId만 읽어 재시도의 최초 승인 여부를 확인한다. */
    @Transactional(readOnly = true)
    public TicketOnceEarnRequestClaim find(CommonOnceEarnCommand command) {
        return repository.findByRequestId(command.requestId().toString())
                .map(request -> claimOf(request, command.computeFingerprint()))
                .orElse(null);
    }

    /** Redis 승인 뒤 영속 요청을 ACCEPTED로 확정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accept(String requestId) {
        repository.findByRequestId(requestId).ifPresent(request -> {
            if (request.getStatus() != TicketOnceEarnRequestStatus.ACCEPTED) {
                request.accept(clock.instant());
            }
        });
    }

    /** PENDING 요청 하나에만 Redis 실행권을 발급한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean beginExecution(String requestId) {
        return repository.changeStatus(requestId, TicketOnceEarnRequestStatus.PENDING,
                TicketOnceEarnRequestStatus.EXECUTING) == 1;
    }

    /** Lua가 잔액을 바꾸지 않은 보정 락 결과면 같은 UUID 재시도를 허용한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseExecution(String requestId) {
        repository.changeStatus(requestId, TicketOnceEarnRequestStatus.EXECUTING,
                TicketOnceEarnRequestStatus.PENDING);
    }

    /** 현재 회원의 승인된 보상 종류를 읽기 전용으로 반환한다. */
    @Transactional(readOnly = true)
    public Set<String> findAcceptedRewardTypes(Long memberId) {
        return repository.findByMemberIdAndStatus(memberId, TicketOnceEarnRequestStatus.ACCEPTED)
                .stream().map(TicketCommonOnceEarnRequest::getRewardType).collect(Collectors.toSet());
    }

    /** 같은 requestId의 fingerprint와 승인 상태를 기존 요청에 대조한다. */
    private TicketOnceEarnRequestClaim claimOf(TicketCommonOnceEarnRequest request, String fingerprint) {
        if (!request.hasSamePayload(fingerprint)) {
            return TicketOnceEarnRequestClaim.REQUEST_ID_CONFLICT;
        }
        return switch (request.getStatus()) {
            case ACCEPTED -> TicketOnceEarnRequestClaim.ACCEPTED;
            case EXECUTING -> TicketOnceEarnRequestClaim.EXECUTING;
            case PENDING -> TicketOnceEarnRequestClaim.PENDING;
        };
    }
}
