package kr.co.cking.winner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
import kr.co.cking.winner.domain.Winner;
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
        Winner initialWinner = winner(100L, 20L, 2L, 1);
        Winner redrawWinner = winner(101L, 21L, 3L, 1);
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
    void Winner의_회원정보가_없으면_공개_결과를_반환하지_않는다() {
        Winner winner = mock(Winner.class);
        when(winner.getMemberId()).thenReturn(2L);
        when(winnerRepository.findAllPublicByEventIdOrderByDrawNoAndRank(EVENT_ID)).thenReturn(List.of(winner));
        when(memberQueryService.findMemberInfosByIds(List.of(2L))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.getPublicWinners(EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    private Winner winner(Long winnerId, Long drawingId, Long memberId, int rankInDrawing) {
        Winner winner = mock(Winner.class);
        when(winner.getId()).thenReturn(winnerId);
        when(winner.getDrawingId()).thenReturn(drawingId);
        when(winner.getMemberId()).thenReturn(memberId);
        when(winner.getRankInDrawing()).thenReturn(rankInDrawing);
        return winner;
    }
}
