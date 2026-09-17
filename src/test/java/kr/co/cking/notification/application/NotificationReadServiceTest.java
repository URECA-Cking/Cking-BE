package kr.co.cking.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationErrorCode;
import kr.co.cking.notification.domain.NotificationType;
import kr.co.cking.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** NotificationReadService의 소유권 검증과 멱등 읽음 처리를 검증한다. */
@ExtendWith(MockitoExtension.class)
class NotificationReadServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationReadService notificationReadService;

    /** 최초 읽음 요청은 readAt을 기록하고 잠금 조회 결과를 반환한다. */
    @Test
    void 최초_읽음_요청은_readAt을_저장한다() {
        Notification notification = notificationOf(1L, null);
        given(memberRepository.existsById(1L)).willReturn(true);
        given(notificationRepository.findByIdForUpdate(10L)).willReturn(Optional.of(notification));

        NotificationReadResult result = notificationReadService.read(1L, 10L);

        assertThat(result.notificationId()).isEqualTo(10L);
        assertThat(result.readAt()).isNotNull();
        assertThat(notification.getReadAt()).isEqualTo(result.readAt());
        verify(notificationRepository).findByIdForUpdate(10L);
    }

    /** 이미 읽은 Notification을 반복 요청해도 최초 readAt을 유지한다. */
    @Test
    void 반복_읽음_요청은_기존_readAt을_유지한다() {
        Instant firstReadAt = Instant.parse("2026-09-17T00:00:00Z");
        Notification notification = notificationOf(1L, firstReadAt);
        given(memberRepository.existsById(1L)).willReturn(true);
        given(notificationRepository.findByIdForUpdate(10L)).willReturn(Optional.of(notification));

        NotificationReadResult first = notificationReadService.read(1L, 10L);
        NotificationReadResult second = notificationReadService.read(1L, 10L);

        assertThat(first.readAt()).isEqualTo(firstReadAt);
        assertThat(second.readAt()).isEqualTo(firstReadAt);
        assertThat(notification.getReadAt()).isEqualTo(firstReadAt);
    }

    /** 존재하지 않는 요청 Member는 Notification을 조회하지 않고 실패한다. */
    @Test
    void 존재하지_않는_Member는_RESOURCE_NOT_FOUND다() {
        given(memberRepository.existsById(99L)).willReturn(false);

        assertThatThrownBy(() -> notificationReadService.read(99L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 존재하지 않는 Notification은 도메인 전용 오류를 반환한다. */
    @Test
    void 존재하지_않는_Notification은_NOTIFICATION_NOT_FOUND다() {
        given(memberRepository.existsById(1L)).willReturn(true);
        given(notificationRepository.findByIdForUpdate(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> notificationReadService.read(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    /** 타 사용자의 Notification은 readAt 변경 없이 접근을 거부한다. */
    @Test
    void 타_사용자_Notification은_FORBIDDEN이다() {
        Notification notification = notificationOf(2L, null);
        given(memberRepository.existsById(1L)).willReturn(true);
        given(notificationRepository.findByIdForUpdate(10L)).willReturn(Optional.of(notification));

        assertThatThrownBy(() -> notificationReadService.read(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        assertThat(notification.getReadAt()).isNull();
    }

    /** 테스트에 필요한 Notification을 생성하고 식별자를 부여한다. */
    private Notification notificationOf(Long memberId, Instant readAt) {
        Notification notification = new Notification(memberId, 2L, 3L, 4L,
                NotificationType.INITIAL_WINNER, "당첨 안내", "축하합니다.", Instant.now());
        ReflectionTestUtils.setField(notification, "id", 10L);
        if (readAt != null) {
            notification.markAsRead(readAt);
        }
        return notification;
    }
}
