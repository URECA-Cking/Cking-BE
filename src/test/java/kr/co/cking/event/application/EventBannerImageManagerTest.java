package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.application.config.EventBannerImageProperties;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.repository.EventBannerImageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EventBannerImageManagerTest {

    private static final String CURRENT = "creator-space-profile-images/42/event-banner/current.jpg";
    private static final String NEXT = "creator-space-profile-images/42/event-banner/next.jpg";
    private final Instant now = Instant.parse("2026-10-22T00:00:00Z");
    private final EventBannerImageRepository images = org.mockito.Mockito.mock(EventBannerImageRepository.class);
    private final EventBannerImageManager manager = new EventBannerImageManager(
            images, new EventBannerImageUrlResolver(new EventBannerImageProperties("https://cdn.example")),
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void 본인_업로드_완료_키를_연결하면_CDN_주소를_저장하고_이전_배너는_삭제_대기로_돌린다() {
        Event event = draft();
        event.changeBannerImage(CURRENT, "https://cdn.example/" + CURRENT);
        given(images.link(NEXT, 42L, now)).willReturn(1);

        manager.apply(event, NEXT);

        assertThat(event.getBannerImageKey()).isEqualTo(NEXT);
        assertThat(event.getBannerImageUrl()).isEqualTo("https://cdn.example/" + NEXT);
        then(images).should().markDeletePending(CURRENT, 42L, now);
    }

    @Test
    void 연결되지_않는_키는_INVALID_EVENT_BANNER_IMAGE이고_현재_배너를_유지한다() {
        Event event = draft();
        event.changeBannerImage(CURRENT, "https://cdn.example/" + CURRENT);
        given(images.link(NEXT, 42L, now)).willReturn(0);

        assertThatThrownBy(() -> manager.apply(event, NEXT))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_EVENT_BANNER_IMAGE);
        assertThat(event.getBannerImageKey()).isEqualTo(CURRENT);
        then(images).should(never()).markDeletePending(any(), any(), any());
    }

    @Test
    void 빈_키는_저장소를_보지_않고_INVALID_EVENT_BANNER_IMAGE다() {
        assertThatThrownBy(() -> manager.apply(draft(), " "))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_EVENT_BANNER_IMAGE);
        then(images).shouldHaveNoInteractions();
    }

    @Test
    void null이면_배너를_떼고_이전_배너는_삭제_대기로_돌린다() {
        Event event = draft();
        event.changeBannerImage(CURRENT, "https://cdn.example/" + CURRENT);

        manager.apply(event, null);

        assertThat(event.getBannerImageKey()).isNull();
        assertThat(event.getBannerImageUrl()).isNull();
        then(images).should().markDeletePending(CURRENT, 42L, now);
    }

    @Test
    void 현재와_같은_키는_다시_연결하지_않는다() {
        Event event = draft();
        event.changeBannerImage(CURRENT, "https://cdn.example/" + CURRENT);

        manager.apply(event, CURRENT);

        assertThat(event.getBannerImageUrl()).isEqualTo("https://cdn.example/" + CURRENT);
        then(images).shouldHaveNoInteractions();
    }

    @Test
    void 초안이_아닌_Event의_배너는_바꿀_수_없다() {
        Event event = draft();
        event.requestApproval();
        given(images.link(NEXT, 42L, now)).willReturn(1);

        assertThatThrownBy(() -> manager.apply(event, NEXT))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_STATE);
    }

    @Test
    void 삭제한_Event의_배너를_삭제_대기로_돌리고_배너가_없으면_아무것도_하지_않는다() {
        manager.release(42L, CURRENT);
        manager.release(42L, null);

        then(images).should().markDeletePending(CURRENT, 42L, now);
        org.mockito.Mockito.verifyNoMoreInteractions(images);
    }

    private Event draft() {
        Event event = new Event(42L, "팬미팅", "설명", now.plusSeconds(86_400), now.plusSeconds(172_800), 1,
                DrawMethod.WEIGHTED, 7L, "550e8400-e29b-41d4-a716-446655440000");
        ReflectionTestUtils.setField(event, "eventId", 1L);
        return event;
    }
}
