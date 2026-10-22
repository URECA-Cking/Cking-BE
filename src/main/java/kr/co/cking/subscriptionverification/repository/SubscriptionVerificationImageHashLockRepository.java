package kr.co.cking.subscriptionverification.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageHashLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** hash별 잠금 행을 생성하고 비관 잠금으로 조회한다. */
public interface SubscriptionVerificationImageHashLockRepository
        extends JpaRepository<SubscriptionVerificationImageHashLock, String> {

    /** 같은 hash의 잠금 행이 없을 때만 생성해 동시 탐지의 기준 행을 확보한다. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO subscription_verification_image_hash_lock (image_sha256, created_at)
            VALUES (:imageSha256, :createdAt)
            """, nativeQuery = true)
    int insertIgnore(@Param("imageSha256") String imageSha256, @Param("createdAt") Instant createdAt);

    /** hash 잠금 행을 잠가 같은 이미지의 최초·재사용 판정을 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select imageHashLock
            from SubscriptionVerificationImageHashLock imageHashLock
            where imageHashLock.imageSha256 = :imageSha256
            """)
    Optional<SubscriptionVerificationImageHashLock> findByImageSha256ForUpdate(
            @Param("imageSha256") String imageSha256
    );
}
