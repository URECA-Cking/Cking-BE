package kr.co.cking.winner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.domain.WinnerErrorCode;
import kr.co.cking.winner.domain.WinnerManagement;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import kr.co.cking.winner.domain.WinnerStatusHistory;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import kr.co.cking.winner.repository.WinnerStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WinnerDeclineServiceTest {

    private static final long WINNER_ID = 100L;
    private static final long USER_ID = 2L;

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private WinnerRepository winnerRepository;

    @Mock
    private WinnerManagementRepository winnerManagementRepository;

    @Mock
    private WinnerStatusHistoryRepository winnerStatusHistoryRepository;

    private WinnerDeclineService service;

    @BeforeEach
    void setUp() {
        service = new WinnerDeclineService(
                memberQueryService,
                winnerRepository,
                winnerManagementRepository,
                winnerStatusHistoryRepository
        );
    }

    @Test
    void 본인_소유_SELECTED_Winner를_DECLINED로_변경하고_변경_이력을_저장한다() {
        Winner winner = winner(USER_ID);
        WinnerManagement management = management();
        when(winnerRepository.findById(WINNER_ID)).thenReturn(Optional.of(winner));
        when(winnerManagementRepository.findByWinnerIdForUpdate(WINNER_ID)).thenReturn(Optional.of(management));

        service.decline(WINNER_ID, USER_ID);

        assertThat(management.getStatus()).isEqualTo(WinnerManagementStatus.DECLINED);
        ArgumentCaptor<WinnerStatusHistory> historyCaptor = ArgumentCaptor.forClass(WinnerStatusHistory.class);
        verify(winnerStatusHistoryRepository).save(historyCaptor.capture());
        WinnerStatusHistory history = historyCaptor.getValue();
        assertThat(history.getWinnerManagementId()).isEqualTo(300L);
        assertThat(history.getStatus()).isEqualTo(WinnerManagementStatus.DECLINED);
        assertThat(history.getReason()).isNull();
        assertThat(history.getChangedBy()).isEqualTo(USER_ID);
    }

    @Test
    void 존재하지_않는_Member면_당첨_정보를_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .when(memberQueryService).validateExists(USER_ID);

        assertThatThrownBy(() -> service.decline(WINNER_ID, USER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(winnerRepository, winnerManagementRepository, winnerStatusHistoryRepository);
    }

    @Test
    void 존재하지_않는_Winner는_WINNER_NOT_FOUND이다() {
        when(winnerRepository.findById(WINNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decline(WINNER_ID, USER_ID))
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.WINNER_NOT_FOUND);

        verifyNoInteractions(winnerManagementRepository, winnerStatusHistoryRepository);
    }

    @Test
    void 다른_사용자의_Winner는_FORBIDDEN이고_운영_정보를_잠그지_않는다() {
        when(winnerRepository.findById(WINNER_ID)).thenReturn(Optional.of(winner(3L)));

        assertThatThrownBy(() -> service.decline(WINNER_ID, USER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);

        verify(winnerManagementRepository, never()).findByWinnerIdForUpdate(WINNER_ID);
        verifyNoInteractions(winnerStatusHistoryRepository);
    }

    @Test
    void 운영_정보가_없으면_WINNER_MANAGEMENT_NOT_FOUND이다() {
        when(winnerRepository.findById(WINNER_ID)).thenReturn(Optional.of(winner(USER_ID)));
        when(winnerManagementRepository.findByWinnerIdForUpdate(WINNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decline(WINNER_ID, USER_ID))
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.WINNER_MANAGEMENT_NOT_FOUND);

        verifyNoInteractions(winnerStatusHistoryRepository);
    }

    @Test
    void 종결_상태에서는_재변경과_이력_추가를_차단한다() {
        WinnerManagement management = management();
        management.decline();
        when(winnerRepository.findById(WINNER_ID)).thenReturn(Optional.of(winner(USER_ID)));
        when(winnerManagementRepository.findByWinnerIdForUpdate(WINNER_ID)).thenReturn(Optional.of(management));

        assertThatThrownBy(() -> service.decline(WINNER_ID, USER_ID))
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.INVALID_STATE);

        verifyNoInteractions(winnerStatusHistoryRepository);
    }

    /** 테스트용 불변 Winner를 만들고 식별자를 설정한다. */
    private Winner winner(Long memberId) {
        Winner winner = Winner.create(10L, 20L, memberId, 1, 3L);
        ReflectionTestUtils.setField(winner, "id", WINNER_ID);
        return winner;
    }

    /** 테스트용 SELECTED 운영 정보를 만들고 식별자를 설정한다. */
    private WinnerManagement management() {
        WinnerManagement management = WinnerManagement.selected(WINNER_ID);
        ReflectionTestUtils.setField(management, "id", 300L);
        return management;
    }
}
