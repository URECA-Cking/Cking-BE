package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.repository.EventRepository;
import org.junit.jupiter.api.Test;

class EventExistenceQueryServiceTest {

    private final EventRepository eventRepository = mock(EventRepository.class);
    private final EventExistenceQueryService service = new EventExistenceQueryService(eventRepository);

    @Test
    void 존재하는_Event는_존재_검증을_통과한다() {
        when(eventRepository.existsById(10L)).thenReturn(true);

        service.validateExists(10L);

        verify(eventRepository).existsById(10L);
    }

    @Test
    void 존재하지_않는_Event는_리소스_없음_오류를_반환한다() {
        when(eventRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.validateExists(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
}
