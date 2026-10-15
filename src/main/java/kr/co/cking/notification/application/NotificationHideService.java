package kr.co.cking.notification.application;

import java.time.Instant;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationErrorCode;
import kr.co.cking.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사용자가 알림함에서 Notification을 숨기는 요청을 처리한다. 알림 행은 지우지 않는다(이슈 #616). */
@Service
@RequiredArgsConstructor
public class NotificationHideService {

    private final MemberRepository memberRepository;
    private final NotificationRepository notificationRepository;

    /** 사용자의 소유 Notification을 최초 한 번만 숨김 처리하고 결과를 반환한다. 이미 숨겼으면 최초 시각을 유지한다. */
    @Transactional
    public NotificationHideResult hide(Long userId, Long notificationId) {
        if (!memberRepository.existsById(userId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        // 읽음 처리와 같이 행을 잠가, 동시에 숨겨도 최초 시각이 하나로 남고 전체 읽음 UPDATE와도 직렬화된다.
        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        if (!notification.isOwnedBy(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        notification.hide(Instant.now());
        return new NotificationHideResult(notification.getNotificationId(), notification.getHiddenAt());
    }
}
