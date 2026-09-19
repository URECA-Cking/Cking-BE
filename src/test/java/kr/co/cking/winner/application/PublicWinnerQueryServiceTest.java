package kr.co.cking.winner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.EventExistenceQueryService;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.winner.repository.PublicWinnerProjection;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicWinnerQueryServiceTest {

    private static final long EVENT_ID = 10L;

    @Mock
    private EventExistenceQueryService eventExistenceQueryService;

    @Mock
    private WinnerRepository winnerRepository;

    @Mock
    private MemberQueryService memberQueryService;

    private PublicWinnerQueryService service;

    @BeforeEach
    void setUp() {
        service = new PublicWinnerQueryService(eventExistenceQueryService, winnerRepository, memberQueryService);
    }

    @Test
    void 공개된_INITIAL과_REDRAW_Winner를_원본_수정_없이_마스킹해_반환한다() {
        PublicWinnerProjection initialWinner = winner(100L, 20L, 0, DrawingType.INITIAL, 2L, 1);
        PublicWinnerProjection redrawWinner = winner(101L, 21L, 1, DrawingType.REDRAW, 3L, 1);
        when(winnerRepository.findAllPublicByEventIdOrderByDrawNoAndRank(EVENT_ID))
                .thenReturn(List.of(initialWinner, redrawWinner));
        when(memberQueryService.findMemberInfosByIds(List.of(2L, 3L))).thenReturn(Map.of(
                2L, new MemberInfo(2L, "권혁준", "010-1234-5678", "first@example.com"),
                3L, new MemberInfo(3L, "김민지", "010-9876-5432", "second@example.com")
        ));

        PublicWinnerQueryResult result = service.getPublicWinners(EVENT_ID);

        verify(eventExistenceQueryService).validateExists(EVENT_ID);
        assertThat(result.eventId()).isEqualTo(EVENT_ID);
        assertThat(result.winners()).extracting(PublicWinnerResult::name).containsExactly("권*준", "김*지");
        assertThat(result.winners()).extracting(PublicWinnerResult::phone)
                .containsExactly("010-****-5678", "010-****-5432");
        assertThat(result.winners()).extracting(PublicWinnerResult::drawingId).containsExactly(20L, 21L);
        assertThat(result.winners()).extracting(PublicWinnerResult::drawNo).containsExactly(0, 1);
        assertThat(result.winners()).extracting(PublicWinnerResult::drawType)
                .containsExactly(DrawingType.INITIAL, DrawingType.REDRAW);
    }

    @Test
    void 존재하지_않는_Event면_공개_Winner를_조회하지_않는다() {
        BusinessException exception = new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        org.mockito.Mockito.doThrow(exception).when(eventExistenceQueryService).validateExists(EVENT_ID);

        assertThatThrownBy(() -> service.getPublicWinners(EVENT_ID))
                .isSameAs(exception);
        verifyNoInteractions(winnerRepository, memberQueryService);
    }

    @Test
    void 공개된_Winner가_없으면_빈_목록을_정상_반환한다() {
        when(winnerRepository.findAllPublicByEventIdOrderByDrawNoAndRank(EVENT_ID)).thenReturn(List.of());
        when(memberQueryService.findMemberInfosByIds(List.of())).thenReturn(Map.of());

        PublicWinnerQueryResult result = service.getPublicWinners(EVENT_ID);

        assertThat(result.eventId()).isEqualTo(EVENT_ID);
        assertThat(result.winners()).isEmpty();
        verify(memberQueryService).findMemberInfosByIds(List.of());
    }

    @Test
    void Winner의_회원정보가_없으면_내부_데이터_정합성_오류로_처리한다() {
        PublicWinnerProjection winner = winner(100L, 20L, 0, DrawingType.INITIAL, 2L, 1);
        when(winnerRepository.findAllPublicByEventIdOrderByDrawNoAndRank(EVENT_ID)).thenReturn(List.of(winner));
        when(memberQueryService.findMemberInfosByIds(List.of(2L))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.getPublicWinners(EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.SYSTEM_ERROR);
    }

    private PublicWinnerProjection winner(
            Long winnerId,
            Long drawingId,
            int drawNo,
            DrawingType drawType,
            Long memberId,
            int rankInDrawing
    ) {
        return new PublicWinnerProjection(winnerId, drawingId, drawNo, drawType, memberId, rankInDrawing);
    }
}
