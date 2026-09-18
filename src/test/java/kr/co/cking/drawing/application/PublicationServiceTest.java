package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import kr.co.cking.drawing.application.DrawingPublicationResult.PublicationOutcome;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.notification.application.WinnerNotificationService;
import kr.co.cking.notification.application.WinnerNotificationTarget;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicationServiceTest {

    @Mock
    private DrawingPublicationService drawingPublicationService;

    @Mock
    private WinnerRepository winnerRepository;

    @Mock
    private WinnerNotificationService winnerNotificationService;

    @InjectMocks
    private PublicationService publicationService;

    @Test
    void 공개_유스케이스는_Drawing_공개_Service에_위임한다() {
        DrawingPublicationResult expected = new DrawingPublicationResult(
                10L, 20L, DrawingVisibility.PUBLIC,
                Instant.parse("2026-09-18T12:00:00Z"), PublicationOutcome.PUBLISHED
        );
        when(drawingPublicationService.publish(10L, 1L)).thenReturn(expected);
        Winner winner = mock(Winner.class);
        when(winner.getId()).thenReturn(100L);
        when(winner.getMemberId()).thenReturn(2L);
        when(winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(10L)).thenReturn(List.of(winner));

        DrawingPublicationResult actual = publicationService.publish(10L, 1L);

        assertThat(actual).isSameAs(expected);
        verify(drawingPublicationService).publish(10L, 1L);
        verify(winnerNotificationService).createInitialWinnerNotifications(
                20L, 10L, List.of(new WinnerNotificationTarget(100L, 2L))
        );
    }

    @Test
    void 멱등_공개_재요청에는_Winner_조회와_Notification_생성을_하지_않는다() {
        DrawingPublicationResult expected = new DrawingPublicationResult(
                10L, 20L, DrawingVisibility.PUBLIC,
                Instant.parse("2026-09-18T12:00:00Z"), PublicationOutcome.ALREADY_PUBLISHED
        );
        when(drawingPublicationService.publish(10L, 1L)).thenReturn(expected);

        DrawingPublicationResult actual = publicationService.publish(10L, 1L);

        assertThat(actual).isSameAs(expected);
        verify(winnerRepository, never()).findAllByDrawingIdOrderByRankInDrawingAsc(10L);
        verify(winnerNotificationService, never()).createInitialWinnerNotifications(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void REDRAW_최초_공개에는_재추첨_당첨_알림_생성을_위임한다() {
        DrawingPublicationResult expected = new DrawingPublicationResult(
                10L, 20L, DrawingType.REDRAW, DrawingVisibility.PUBLIC,
                Instant.parse("2026-09-18T12:00:00Z"), PublicationOutcome.PUBLISHED
        );
        when(drawingPublicationService.publish(10L, 1L)).thenReturn(expected);
        Winner winner = mock(Winner.class);
        when(winner.getId()).thenReturn(100L);
        when(winner.getMemberId()).thenReturn(2L);
        when(winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(10L)).thenReturn(List.of(winner));

        publicationService.publish(10L, 1L);

        verify(winnerNotificationService).createRedrawWinnerNotifications(
                20L, 10L, List.of(new WinnerNotificationTarget(100L, 2L))
        );
        verify(winnerNotificationService, never()).createInitialWinnerNotifications(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }
}
