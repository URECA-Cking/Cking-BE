package kr.co.cking.notification.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import kr.co.cking.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Notification 영속화와 사용자별 조회·읽음 처리용 잠금 조회를 제공한다. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 특정 Member가 소유한 Notification만 Page 조건에 따라 조회한다. */
    Page<Notification> findByMemberId(Long memberId, Pageable pageable);

    /** 동시 읽음 처리의 최초 시각을 하나로 보장하기 위해 Notification 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select notification from Notification notification where notification.notificationId = :notificationId")
    Optional<Notification> findByIdForUpdate(@Param("notificationId") Long notificationId);
}
