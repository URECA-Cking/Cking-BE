package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EventDrawingQueryServiceTest {

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private EventDrawingQueryService service;

    @Test
    void Drawing_검증에_필요한_Event_상태를_읽기_전용_값으로_반환한다() {
        Event event = Event.builder()
                .creatorId(2L)
                .requestId("request-1")
                .title("이벤트")
                .startAt(Instant.parse("2026-09-15T00:00:00Z"))
                .endAt(Instant.parse("2026-09-16T00:00:00Z"))
                .winnerCount(1)
                .drawMethod("WEIGHTED")
                .status(EventStatus.CLOSED)
                .createdBy(2L)
                .createdAt(Instant.parse("2026-09-14T00:00:00Z"))
                .build();
        ReflectionTestUtils.setField(event, "eventId", 10L);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        EventDrawingSource source = service.getDrawingSource(10L);

        assertThat(source.eventId()).isEqualTo(10L);
        assertThat(source.status()).isEqualTo(EventStatus.CLOSED);
        assertThat(source.deletedAt()).isNull();
    }

    @Test
    void 존재하지_않는_Event은_RESOURCE_NOT_FOUND를_반환한다() {
        when(eventRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDrawingSource(10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
}
