package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestVacancy;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import kr.co.cking.redraw.repository.RedrawRequestVacancyRepository;
import kr.co.cking.redraw.repository.RedrawVacancyCandidateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Event 잠금 안의 RedrawRequest 결원 계산·점유 저장을 검증한다. */
@ExtendWith(MockitoExtension.class)
class RedrawRequestCreationPersistenceServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final long EVENT_ID = 10L;
    private static final long ORIGINAL_DRAWING_ID = 20L;
    private static final String IDEMPOTENCY_KEY = "d2719c4a-1f9b-4dc4-a656-9a4bb37d8e70";

    @Mock
    private EventDrawingQueryService eventDrawingQueryService;

    @Mock
    private DrawingRepository drawingRepository;

    @Mock
    private RedrawVacancyCandidateRepository redrawVacancyCandidateRepository;

    @Mock
    private RedrawRequestRepository redrawRequestRepository;

    @Mock
    private RedrawRequestVacancyRepository redrawRequestVacancyRepository;

    private RedrawRequestCreationPersistenceService service;

    /** 각 테스트가 독립된 Mock 의존성으로 결원 확정 Service를 구성한다. */
    @BeforeEach
    void setUp() {
        service = new RedrawRequestCreationPersistenceService(
                eventDrawingQueryService, drawingRepository, redrawVacancyCandidateRepository,
                redrawRequestRepository, redrawRequestVacancyRepository
        );
    }

    /** DECLINED·DISQUALIFIED 후보에서 진행 중 요청이 점유한 Winner를 제외해 함께 저장한다. */
    @Test
    void 미점유_결원만_선정해_RedrawRequest와_점유목록을_저장한다() {
        Drawing initialDrawing = initialDrawing();
        RedrawRequest persisted = org.mockito.Mockito.mock(RedrawRequest.class);
        when(persisted.getId()).thenReturn(30L);
        when(eventDrawingQueryService.getDrawingSourceForUpdate(EVENT_ID))
                .thenReturn(new EventDrawingSource(EVENT_ID, EventStatus.PUBLISHED, null));
        when(drawingRepository.findByEventIdAndDrawNo(EVENT_ID, 0)).thenReturn(Optional.of(initialDrawing));
        when(redrawVacancyCandidateRepository.findVacancyWinnerIds(eq(EVENT_ID), eq(ORIGINAL_DRAWING_ID), anyList()))
                .thenReturn(List.of(101L, 102L, 103L));
        when(redrawRequestVacancyRepository.findOccupiedWinnerIdsInProgress(List.of(101L, 102L, 103L)))
                .thenReturn(List.of(102L));
        when(redrawRequestRepository.saveAndFlush(any(RedrawRequest.class))).thenReturn(persisted);

        RedrawRequest result = service.create(command());

        assertThat(result).isSameAs(persisted);
        ArgumentCaptor<RedrawRequest> requestCaptor = ArgumentCaptor.forClass(RedrawRequest.class);
        verify(redrawRequestRepository).saveAndFlush(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getEventId()).isEqualTo(EVENT_ID);
        assertThat(requestCaptor.getValue().getOriginalDrawingId()).isEqualTo(ORIGINAL_DRAWING_ID);
        assertThat(requestCaptor.getValue().getVacancyCount()).isEqualTo(2);
        ArgumentCaptor<List<RedrawRequestVacancy>> vacancyCaptor = ArgumentCaptor.forClass(List.class);
        verify(redrawRequestVacancyRepository).saveAll(vacancyCaptor.capture());
        assertThat(vacancyCaptor.getValue()).extracting(RedrawRequestVacancy::getWinnerId)
                .containsExactly(101L, 103L);
    }

    /** PUBLISHED가 아닌 Event에서는 Drawing과 결원 조회를 수행하지 않는다. */
    @Test
    void PUBLISHED가_아닌_Event는_INVALID_STATE다() {
        when(eventDrawingQueryService.getDrawingSourceForUpdate(EVENT_ID))
                .thenReturn(new EventDrawingSource(EVENT_ID, EventStatus.CLOSED, null));

        assertThatThrownBy(() -> service.create(command()))
                .hasFieldOrPropertyWithValue("errorCode", RedrawErrorCode.INVALID_STATE);

        verifyNoInteractions(drawingRepository, redrawVacancyCandidateRepository,
                redrawRequestRepository, redrawRequestVacancyRepository);
    }

    /** 최초 INITIAL Drawing이 없으면 Event 단위 명령의 상태 위반으로 차단한다. */
    @Test
    void INITIAL_Drawing이_없으면_INVALID_STATE다() {
        when(eventDrawingQueryService.getDrawingSourceForUpdate(EVENT_ID))
                .thenReturn(new EventDrawingSource(EVENT_ID, EventStatus.PUBLISHED, null));
        when(drawingRepository.findByEventIdAndDrawNo(EVENT_ID, 0)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(command()))
                .hasFieldOrPropertyWithValue("errorCode", RedrawErrorCode.INVALID_STATE);

        verifyNoInteractions(redrawVacancyCandidateRepository,
                redrawRequestRepository, redrawRequestVacancyRepository);
    }

    /** 미점유 DECLINED·DISQUALIFIED Winner가 없으면 빈 요청을 저장하지 않는다. */
    @Test
    void 결원이_없으면_NO_REDRAW_VACANCY다() {
        Drawing initialDrawing = initialDrawing();
        when(eventDrawingQueryService.getDrawingSourceForUpdate(EVENT_ID))
                .thenReturn(new EventDrawingSource(EVENT_ID, EventStatus.PUBLISHED, null));
        when(drawingRepository.findByEventIdAndDrawNo(EVENT_ID, 0)).thenReturn(Optional.of(initialDrawing));
        when(redrawVacancyCandidateRepository.findVacancyWinnerIds(eq(EVENT_ID), eq(ORIGINAL_DRAWING_ID), anyList()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.create(command()))
                .hasFieldOrPropertyWithValue("errorCode", RedrawErrorCode.NO_REDRAW_VACANCY);

        verifyNoInteractions(redrawRequestRepository, redrawRequestVacancyRepository);
    }

    /** 테스트용 PUBLISHED Event의 최초 INITIAL Drawing을 만든다. */
    private Drawing initialDrawing() {
        Drawing drawing = org.mockito.Mockito.mock(Drawing.class);
        when(drawing.getId()).thenReturn(ORIGINAL_DRAWING_ID);
        when(drawing.getDrawType()).thenReturn(DrawingType.INITIAL);
        return drawing;
    }

    /** 테스트용 유효한 생성 명령을 만든다. */
    private RedrawRequestCreateCommand command() {
        return new RedrawRequestCreateCommand(ADMIN_ID, EVENT_ID, "재추첨 사유", IDEMPOTENCY_KEY);
    }
}
