package kr.co.cking.winner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import kr.co.cking.winner.repository.MyWinnerProjection;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MyWinnerQueryServiceTest {

    private static final long USER_ID = 2L;
    private static final Instant WINNER_CREATED_AT = Instant.parse("2026-09-19T10:00:00Z");
    private static final Instant MANAGEMENT_UPDATED_AT = Instant.parse("2026-09-19T11:00:00Z");

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private WinnerRepository winnerRepository;

    private MyWinnerQueryService service;

    @BeforeEach
    void setUp() {
        service = new MyWinnerQueryService(memberQueryService, winnerRepository);
    }

    @Test
    void 본인의_INITIAL과_REDRAW_Winner_불변_데이터와_현재_운영_상태를_반환한다() {
        MyWinnerProjection initial = winner(100L, 10L, 20L, 0, DrawingType.INITIAL, 1,
                3L, 300L, WinnerManagementStatus.SELECTED);
        MyWinnerProjection redraw = winner(101L, 10L, 21L, 1, DrawingType.REDRAW, 1,
                1L, 301L, WinnerManagementStatus.RECEIVED);
        when(winnerRepository.findAllWithManagementByMemberIdOrderByDrawNoAndRank(USER_ID))
                .thenReturn(List.of(initial, redraw));

        List<MyWinnerResult> results = service.getMyWinners(USER_ID);

        verify(memberQueryService).validateExists(USER_ID);
        assertThat(results).extracting(MyWinnerResult::winnerId).containsExactly(100L, 101L);
        assertThat(results).extracting(MyWinnerResult::drawType)
                .containsExactly(DrawingType.INITIAL, DrawingType.REDRAW);
        assertThat(results).extracting(MyWinnerResult::appliedTicketCount).containsExactly(3L, 1L);
        assertThat(results).extracting(MyWinnerResult::winnerManagementStatus)
                .containsExactly(WinnerManagementStatus.SELECTED, WinnerManagementStatus.RECEIVED);
        assertThat(results).extracting(MyWinnerResult::winnerManagementUpdatedAt)
                .containsOnly(MANAGEMENT_UPDATED_AT);
    }

    @Test
    void 존재하지_않는_Member면_Winner를_조회하지_않는다() {
        BusinessException exception = new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        doThrow(exception).when(memberQueryService).validateExists(USER_ID);

        assertThatThrownBy(() -> service.getMyWinners(USER_ID)).isSameAs(exception);

        verifyNoInteractions(winnerRepository);
    }

    @Test
    void 다른_사용자의_Winner는_조회_조건에_포함되지_않아_빈_목록을_반환한다() {
        when(winnerRepository.findAllWithManagementByMemberIdOrderByDrawNoAndRank(USER_ID))
                .thenReturn(List.of());

        List<MyWinnerResult> results = service.getMyWinners(USER_ID);

        verify(winnerRepository).findAllWithManagementByMemberIdOrderByDrawNoAndRank(USER_ID);
        assertThat(results).isEmpty();
    }

    /** 테스트에 필요한 Winner·WinnerManagement 읽기 전용 결합 결과를 만든다. */
    private MyWinnerProjection winner(
            Long winnerId,
            Long eventId,
            Long drawingId,
            int drawNo,
            DrawingType drawType,
            int rankInDrawing,
            long appliedTicketCount,
            Long winnerManagementId,
            WinnerManagementStatus winnerManagementStatus
    ) {
        return new MyWinnerProjection(
                winnerId,
                eventId,
                drawingId,
                drawNo,
                drawType,
                rankInDrawing,
                appliedTicketCount,
                WINNER_CREATED_AT,
                winnerManagementId,
                winnerManagementStatus,
                WINNER_CREATED_AT,
                MANAGEMENT_UPDATED_AT
        );
    }
}
