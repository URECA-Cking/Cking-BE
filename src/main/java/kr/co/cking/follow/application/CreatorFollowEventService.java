package kr.co.cking.follow.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.common.event.CreatorFollowCreated;
import kr.co.cking.follow.repository.CreatorFollowEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 이벤트 원본과 처리 상태는 Follow가 관리하며 호출자의 트랜잭션에 참여한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CreatorFollowEventService {
    private final CreatorFollowEventRepository repository;
    private final CreatorFollowRecoverySettings settings;

    public void append(CreatorFollowCreated event) {
        repository.append(event, event.followedAt().plus(settings.initialDelay()));
    }

    public Optional<CreatorFollowCreated> findPending(UUID id) {
        return repository.findPending(id);
    }

    public List<UUID> pendingIds(Instant now, int limit) {
        return repository.pendingIds(now, limit);
    }

    public Optional<CreatorFollowCreated> findRecoverable(UUID id, Instant now) {
        return repository.findRecoverable(id, now);
    }

    public void complete(UUID id, Instant now) {
        repository.complete(id, now);
    }

    public void defer(UUID id, Instant failedAt) {
        repository.defer(id, failedAt);
    }

    public int deleteExpired(Instant cutoff, int limit) {
        return repository.deleteExpired(cutoff, limit);
    }
}
