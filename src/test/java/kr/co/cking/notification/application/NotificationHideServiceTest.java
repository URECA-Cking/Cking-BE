package kr.co.cking.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

/** NotificationHideService의 소유권 검증과 멱등 숨김 처리를 검증한다. */
@ExtendWith(MockitoExtension.class)
class NotificationHideServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationHideService notificationHideService;

    /** 최초 숨김 요청은 hiddenAt을 기록하고 행 잠금 조회를 거친다. */
    @Test
    void 최초_숨김_요청은_hiddenAt을_저장한다() {
        Notification notification = notificationOf(1L, null);
        given(memberRepository.existsById(1L)).willReturn(true);
        given(notificationRepository.findByIdForUpdate(10L)).willReturn(Optional.of(notification));

        NotificationHideResult result = notificationHideService.hide(1L, 10L);

        assertThat(result.notificationId()).isEqualTo(10L);
        assertThat(result.hiddenAt()).isNotNull();
        assertThat(notification.getHiddenAt()).isEqualTo(result.hiddenAt());
        assertThat(notification.isHidden()).isTrue();
        verify(notificationRepository).findByIdForUpdate(10L);
    }

    /** 이미 숨긴 Notification을 반복 요청해도 오류 없이 최초 hiddenAt을 유지한다. */
    @Test
    void 반복_숨김_요청은_기존_hiddenAt을_유지한다() {
        Instant firstHiddenAt = Instant.parse("2026-10-16T00:00:00Z");
        Notification notification = notificationOf(1L, firstHiddenAt);
        given(memberRepository.existsById(1L)).willReturn(true);
        given(notificationRepository.findByIdForUpdate(10L)).willReturn(Optional.of(notification));

        NotificationHideResult first = notificationHideService.hide(1L, 10L);
        NotificationHideResult second = notificationHideService.hide(1L, 10L);

        assertThat(first.hiddenAt()).isEqualTo(firstHiddenAt);
        assertThat(second.hiddenAt()).isEqualTo(firstHiddenAt);
        assertThat(notification.getHiddenAt()).isEqualTo(firstHiddenAt);
    }

    /** 숨김은 읽음 상태를 바꾸지 않는다. */
    @Test
    void 숨겨도_읽음_상태는_바뀌지_않는다() {
        Notification notification = notificationOf(1L, null);
        given(memberRepository.existsById(1L)).willReturn(true);
        given(notificationRepository.findByIdForUpdate(10L)).willReturn(Optional.of(notification));

        notificationHideService.hide(1L, 10L);

        assertThat(notification.getReadAt()).isNull();
    }

    /** 존재하지 않는 요청 Member는 Notification을 조회하지 않고 실패한다. */
    @Test
    void 존재하지_않는_Member는_RESOURCE_NOT_FOUND다() {
        given(memberRepository.existsById(99L)).willReturn(false);

        assertThatThrownBy(() -> notificationHideService.hide(99L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(notificationRepository);
    }

    /** 존재하지 않는 Notification은 도메인 전용 오류를 반환한다. */
    @Test
    void 존재하지_않는_Notification은_NOTIFICATION_NOT_FOUND다() {
        given(memberRepository.existsById(1L)).willReturn(true);
        given(notificationRepository.findByIdForUpdate(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> notificationHideService.hide(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    /** 타 사용자의 Notification은 hiddenAt 변경 없이 접근을 거부한다. */
    @Test
    void 타_사용자_Notification은_FORBIDDEN이고_숨겨지지_않는다() {
        Notification notification = notificationOf(2L, null);
        given(memberRepository.existsById(1L)).willReturn(true);
        given(notificationRepository.findByIdForUpdate(10L)).willReturn(Optional.of(notification));

        assertThatThrownBy(() -> notificationHideService.hide(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        assertThat(notification.getHiddenAt()).isNull();
    }

    /** 테스트에 필요한 Notification을 생성하고 식별자를 부여한다. */
    private Notification notificationOf(Long memberId, Instant hiddenAt) {
        Notification notification = new Notification(memberId, 2L, 3L, 4L,
                NotificationType.INITIAL_WINNER, "당첨 안내", "축하합니다.", Instant.now());
        ReflectionTestUtils.setField(notification, "notificationId", 10L);
        if (hiddenAt != null) {
            notification.hide(hiddenAt);
        }
        return notification;
    }
}
