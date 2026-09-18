package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DrawingAdminQueryServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final long DRAWING_ID = 20L;

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private DrawingRepository drawingRepository;

    @Mock
    private WinnerRepository winnerRepository;

    private DrawingAdminQueryService service;

    @BeforeEach
    void setUp() {
        service = new DrawingAdminQueryService(memberQueryService, drawingRepository, winnerRepository);
    }

    @Test
    void 관리자는_Drawing_기본정보를_조회한다() {
        Drawing drawing = drawing(DrawingStatus.COMPLETED);
        when(drawingRepository.findById(DRAWING_ID)).thenReturn(Optional.of(drawing));

        DrawingQueryResult result = service.getDrawing(DRAWING_ID, ADMIN_ID);

        verify(memberQueryService).validateAdmin(ADMIN_ID);
        assertThat(result.drawingId()).isEqualTo(DRAWING_ID);
        assertThat(result.eventId()).isEqualTo(10L);
        assertThat(result.snapshotId()).isEqualTo(30L);
        assertThat(result.drawType()).isEqualTo(DrawingType.INITIAL);
        assertThat(result.status()).isEqualTo(DrawingStatus.COMPLETED);
        assertThat(result.visibility()).isEqualTo(DrawingVisibility.PRIVATE);
    }

    @Test
    void 결과는_완료된_Drawing의_Winner를_순위순으로_회원정보와_함께_반환한다() {
        Drawing drawing = drawingForResult(DrawingStatus.COMPLETED);
        Winner first = winner(100L, 2L, 1, 7L);
        Winner second = winner(101L, 3L, 2, 3L);
        when(drawingRepository.findById(DRAWING_ID)).thenReturn(Optional.of(drawing));
        when(winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(DRAWING_ID))
                .thenReturn(List.of(first, second));
        when(memberQueryService.findMemberInfosByIds(List.of(2L, 3L))).thenReturn(Map.of(
                2L, new MemberInfo(2L, "둘", "010-0000-0002", "two@example.com"),
                3L, new MemberInfo(3L, "셋", "010-0000-0003", "three@example.com")
        ));

        DrawingResultQuery result = service.getDrawingResult(DRAWING_ID, ADMIN_ID);

        verify(memberQueryService).validateAdmin(ADMIN_ID);
        verify(memberQueryService).findMemberInfosByIds(List.of(2L, 3L));
        assertThat(result.drawingId()).isEqualTo(DRAWING_ID);
        assertThat(result.winners()).extracting(DrawingWinnerResult::rankInDrawing).containsExactly(1, 2);
        assertThat(result.winners()).extracting(DrawingWinnerResult::name).containsExactly("둘", "셋");
        assertThat(result.winners()).extracting(DrawingWinnerResult::phone)
                .containsExactly("010-0000-0002", "010-0000-0003");
        assertThat(result.winners()).extracting(DrawingWinnerResult::email)
                .containsExactly("two@example.com", "three@example.com");
        assertThatThrownBy(() -> result.winners().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 완료되지_않은_Drawing의_결과는_조회할_수_없다() {
        Drawing drawing = drawingForResult(DrawingStatus.RUNNING);
        when(drawingRepository.findById(DRAWING_ID)).thenReturn(Optional.of(drawing));

        assertThatThrownBy(() -> service.getDrawingResult(DRAWING_ID, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DrawingErrorCode.DRAWING_NOT_COMPLETED);
        verifyNoInteractions(winnerRepository);
    }

    @Test
    void 없는_Drawing은_DRAWING_NOT_FOUND다() {
        when(drawingRepository.findById(DRAWING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDrawing(DRAWING_ID, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DrawingErrorCode.DRAWING_NOT_FOUND);
    }

    @Test
    void 관리자_검증에_실패하면_Drawing을_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.getDrawing(DRAWING_ID, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        verifyNoInteractions(drawingRepository, winnerRepository);
    }

    @Test
    void Winner의_회원정보가_없으면_결과를_반환하지_않는다() {
        Drawing drawing = drawingForResult(DrawingStatus.COMPLETED);
        Winner winner = mock(Winner.class);
        when(winner.getMemberId()).thenReturn(2L);
        when(drawingRepository.findById(DRAWING_ID)).thenReturn(Optional.of(drawing));
        when(winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(DRAWING_ID)).thenReturn(List.of(winner));
        when(memberQueryService.findMemberInfosByIds(List.of(2L))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.getDrawingResult(DRAWING_ID, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    private Drawing drawing(DrawingStatus status) {
        Drawing drawing = mock(Drawing.class);
        when(drawing.getId()).thenReturn(DRAWING_ID);
        when(drawing.getEventId()).thenReturn(10L);
        when(drawing.getSnapshotId()).thenReturn(30L);
        when(drawing.getDrawNo()).thenReturn(0);
        when(drawing.getDrawType()).thenReturn(DrawingType.INITIAL);
        when(drawing.getStatus()).thenReturn(status);
        when(drawing.getVisibility()).thenReturn(DrawingVisibility.PRIVATE);
        when(drawing.getDrawMethod()).thenReturn("WEIGHTED");
        when(drawing.getAlgorithmVersion()).thenReturn("WEIGHTED_V1");
        when(drawing.getWinnerCount()).thenReturn(2);
        when(drawing.getRequestedBy()).thenReturn(ADMIN_ID);
        when(drawing.getCreatedAt()).thenReturn(Instant.parse("2026-09-17T00:00:00Z"));
        return drawing;
    }

    private Drawing drawingForResult(DrawingStatus status) {
        Drawing drawing = mock(Drawing.class);
        when(drawing.getStatus()).thenReturn(status);
        return drawing;
    }

    private Winner winner(Long winnerId, Long memberId, int rank, long ticketCount) {
        Winner winner = mock(Winner.class);
        when(winner.getId()).thenReturn(winnerId);
        when(winner.getEventId()).thenReturn(10L);
        when(winner.getDrawingId()).thenReturn(DRAWING_ID);
        when(winner.getMemberId()).thenReturn(memberId);
        when(winner.getRankInDrawing()).thenReturn(rank);
        when(winner.getAppliedTicketCount()).thenReturn(ticketCount);
        when(winner.getCreatedAt()).thenReturn(Instant.parse("2026-09-17T00:02:00Z"));
        return winner;
    }
}
