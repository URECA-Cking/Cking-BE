package kr.co.cking.notification.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationErrorCode;
import kr.co.cking.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** 사용자의 Notification 읽음 상태 변경을 처리한다. */
@Service
@RequiredArgsConstructor
public class NotificationReadService {

    private final MemberRepository memberRepository;
    private final NotificationRepository notificationRepository;

    /** 사용자의 소유 Notification을 최초 한 번만 읽음 처리하고 결과를 반환한다. */
    @Transactional
    public NotificationReadResult read(Long userId, Long notificationId) {
        validateMember(userId);
        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        if (!notification.isOwnedBy(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        notification.markAsRead(Instant.now());
        return new NotificationReadResult(notification.getNotificationId(), notification.getReadAt());
    }

    /** 요청 사용자 존재 여부를 검증한다. */
    private void validateMember(Long userId) {
        if (!memberRepository.existsById(userId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
