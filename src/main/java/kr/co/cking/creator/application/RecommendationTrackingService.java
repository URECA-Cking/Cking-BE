package kr.co.cking.creator.application;

import java.time.Instant;
import kr.co.cking.common.event.CreatorFollowCreated;
import kr.co.cking.common.repository.DatabaseTime;
import kr.co.cking.common.repository.MemberActivityLock;
import kr.co.cking.follow.application.CreatorFollowEventService;
import java.util.List;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.creator.application.dto.RecommendationEventCommand;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.RecommendationTrackingRepository;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 5)
public class RecommendationTrackingService {
    private final RecommendationTrackingRepository repository;
    private final MemberRepository memberRepository;
    private final RecommendationTrackingSettings settings;
    private final DatabaseTime databaseTime;
    private final MemberActivityLock activityLock;
    private final CreatorFollowEventService followEvents;

    public UUID recordSnapshot(Long memberId, PersonalizedCreatorRecommendationView view) {
        UUID requestId = UUID.randomUUID();
        Instant now = now();
        repository.saveSnapshot(requestId, memberId, view, now, now.plus(settings.requestTtl()));
        return requestId;
    }

    public int collect(Long memberId, List<RecommendationEventCommand> events) {
        if (events == null || events.isEmpty() || events.size() > 50 || events.stream().anyMatch(event ->
                event == null || event.eventId() == null || event.recommendationRequestId() == null
                        || event.creatorId() == null || event.creatorId() <= 0 || event.eventType() == null)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        lockMember(memberId);
        // 잠금 대기 이후를 수신 시각으로 사용해 승인 순서를 보존한다.
        Instant receivedAt = now();
        for (var event : events) {
            var request = repository.findRequest(event.recommendationRequestId())
                    .orElseThrow(() -> new BusinessException(CreatorErrorCode.RECOMMENDATION_REQUEST_NOT_FOUND));
            if (!request.memberId().equals(memberId)) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            }
            var receipt = repository.findReceipt(event.eventId());
            if (receipt.isPresent()) {
                if (!receipt.get().equals(event)) {
                    throw new BusinessException(CreatorErrorCode.RECOMMENDATION_EVENT_CONFLICT);
                }
                continue;
            }
            if (!receivedAt.isBefore(request.expiresAt())) {
                throw new BusinessException(CreatorErrorCode.RECOMMENDATION_REQUEST_EXPIRED);
            }
            if (!repository.containsCard(event.recommendationRequestId(), event.creatorId())) {
                throw new BusinessException(CreatorErrorCode.RECOMMENDATION_CANDIDATE_NOT_RETURNED);
            }
            try {
                repository.insertReceipt(memberId, event, receivedAt);
            } catch (DuplicateKeyException collision) {
                // 다른 회원의 동일 전역 ID 경합도 내용 충돌이며 전체 배치를 롤백한다.
                throw new BusinessException(CreatorErrorCode.RECOMMENDATION_EVENT_CONFLICT);
            }
            repository.insertInteractionIfAbsent(memberId, event, receivedAt);
        }
        return events.size();
    }

    public void recordFollow(Long memberId, Long creatorId, Instant followedAt) {
        lockMember(memberId);
        repository.findLastClick(memberId, creatorId, followedAt.minus(settings.attributionWindow()), followedAt)
                .ifPresent(click -> repository.insertConversionIfAbsent(memberId, creatorId, followedAt,
                        click, settings.attributionWindow().toSeconds()));
    }

    public int cleanUp() {
        return repository.deleteExpiredHistory(now().minus(settings.retention()), 100);
    }

    public int cleanUpFollowEvents() {
        return followEvents.deleteExpired(now().minus(settings.retention()), 100);
    }

    public List<UUID> pendingFollowEvents() {
        return followEvents.pendingIds(now(), 100);
    }

    public void processFollowEvent(UUID eventId) {
        followEvents.findPending(eventId).ifPresent(this::processFollowEvent);
    }

    public void recoverFollowEvent(UUID eventId) {
        // 목록 조회 뒤 다른 인스턴스가 연기한 이벤트도 행 잠금 아래에서 재판정한다.
        followEvents.findRecoverable(eventId, now()).ifPresent(this::processFollowEvent);
    }

    private void processFollowEvent(CreatorFollowCreated original) {
        lockMember(original.memberId());
        // 영속 이벤트의 FOR UPDATE가 큐와 여러 인스턴스의 복구 잡을 직렬화한다.
        repository.findLastClick(original.memberId(), original.creatorId(),
                        original.followedAt().minus(settings.attributionWindow()), original.followedAt())
                .ifPresent(click -> repository.insertConversionIfAbsent(original.memberId(), original.creatorId(),
                        original.followedAt(), click, settings.attributionWindow().toSeconds()));
        followEvents.complete(original.eventId(), now());
    }

    public void deferFollowEvent(UUID eventId) {
        followEvents.defer(eventId, now());
    }

    private void lockMember(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        activityLock.lock(memberId);
    }

    private Instant now() {
        return databaseTime.now();
    }
}
