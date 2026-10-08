package kr.co.cking.creator.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
public class RecommendationTrackingService {
    private final RecommendationTrackingRepository repository;
    private final MemberRepository memberRepository;
    private final RecommendationTrackingSettings settings;
    private final Clock clock;

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
            if (!receivedAt.isBefore(request.expiresAt())) {
                throw new BusinessException(CreatorErrorCode.RECOMMENDATION_REQUEST_EXPIRED);
            }
            if (!repository.containsCard(event.recommendationRequestId(), event.creatorId())) {
                throw new BusinessException(CreatorErrorCode.RECOMMENDATION_CANDIDATE_NOT_RETURNED);
            }
            var receipt = repository.findReceipt(event.eventId());
            if (receipt.isPresent()) {
                if (!receipt.get().equals(event)) {
                    throw new BusinessException(CreatorErrorCode.RECOMMENDATION_EVENT_CONFLICT);
                }
                continue;
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
        return repository.deleteExpiredHistory(now().minus(settings.retention()));
    }

    private void lockMember(Long memberId) {
        memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
