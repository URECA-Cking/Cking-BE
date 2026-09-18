package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import kr.co.cking.drawing.application.DrawingPublicationResult.PublicationOutcome;
import kr.co.cking.drawing.domain.DrawingVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicationServiceTest {

    @Mock
    private DrawingPublicationService drawingPublicationService;

    @InjectMocks
    private PublicationService publicationService;

    @Test
    void 공개_유스케이스는_Drawing_공개_Service에_위임한다() {
        DrawingPublicationResult expected = new DrawingPublicationResult(
                10L, 20L, DrawingVisibility.PUBLIC,
                Instant.parse("2026-09-18T12:00:00Z"), PublicationOutcome.PUBLISHED
        );
        when(drawingPublicationService.publish(10L, 1L)).thenReturn(expected);

        DrawingPublicationResult actual = publicationService.publish(10L, 1L);

        assertThat(actual).isSameAs(expected);
        verify(drawingPublicationService).publish(10L, 1L);
    }
}
