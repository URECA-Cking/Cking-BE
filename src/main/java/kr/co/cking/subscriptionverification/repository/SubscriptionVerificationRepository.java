package kr.co.cking.subscriptionverification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionVerificationRepository extends JpaRepository<SubscriptionVerification, Long> {

    Optional<SubscriptionVerification> findByRequestId(String requestId);

    Optional<SubscriptionVerification> findByMemberIdAndCreatorIdAndMissionIdAndStatus(
            Long memberId,
            Long creatorId,
            Long missionId,
            SubscriptionVerificationStatus status
    );

    boolean existsByMemberIdAndCreatorIdAndMissionIdAndStatusIn(
            Long memberId,
            Long creatorId,
            Long missionId,
            Collection<SubscriptionVerificationStatus> statuses
    );

    boolean existsByCreatorIdAndStatusIn(
            Long creatorId,
            Collection<SubscriptionVerificationStatus> statuses
    );

    Optional<SubscriptionVerification> findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
            Long memberId,
            Long creatorId,
            Long missionId
    );

    long countByMemberIdAndCreatorIdAndMissionIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long memberId,
            Long creatorId,
            Long missionId,
            Instant from,
            Instant to
    );

    List<SubscriptionVerification> findAllByMemberIdAndCreatorIdAndMissionId(
            Long memberId,
            Long creatorId,
            Long missionId
    );
}
