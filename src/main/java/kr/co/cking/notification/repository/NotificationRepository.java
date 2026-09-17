package kr.co.cking.notification.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import kr.co.cking.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Notification 영속화와 읽음 처리용 잠금 조회를 제공한다. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 동시 읽음 처리의 최초 시각을 하나로 보장하기 위해 Notification 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select notification from Notification notification where notification.id = :notificationId")
    Optional<Notification> findByIdForUpdate(@Param("notificationId") Long notificationId);
}
