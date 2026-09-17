package kr.co.cking.notification.repository;

import kr.co.cking.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 특정 Member가 소유한 Notification만 Page 조건에 따라 조회한다. */
    Page<Notification> findByMemberId(Long memberId, Pageable pageable);
}
