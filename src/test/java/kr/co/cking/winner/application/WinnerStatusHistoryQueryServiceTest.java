package kr.co.cking.winner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.domain.MemberRole;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Winner 상태 이력 조회의 역할별 접근 제어와 응답 변환을 검증한다. */
@ExtendWith(MockitoExtension.class)
class WinnerStatusHistoryQueryServiceTest {

    private static final long WINNER_ID = 100L;
    private static final long USER_ID = 2L;
    private static final long ADMIN_ID = 1L;

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private WinnerRepository winnerRepository;

    @Mock
    private WinnerManagementRepository winnerManagementRepository;

    @Mock
    private WinnerStatusHistoryRepository winnerStatusHistoryRepository;

    private WinnerStatusHistoryQueryService service;

    /** 각 테스트가 독립된 조회 의존성을 사용하는 Service를 만든다. */
    @BeforeEach
    void setUp() {
        service = new WinnerStatusHistoryQueryService(
                memberQueryService,
                winnerRepository,
                winnerManagementRepository,
                winnerStatusHistoryRepository
        );
    }

    /** USER는 본인 Winner의 변경 전후 상태와 감사 정보를 시간순으로 조회한다. */
    @Test
    void USER는_본인_Winner_상태_이력을_조회한다() {
        Winner winner = winner(USER_ID);
        WinnerManagement management = management(300L);
        WinnerStatusHistory history = history(
                900L, WinnerManagementStatus.SELECTED, WinnerManagementStatus.DECLINED, null, USER_ID
        );
        when(memberQueryService.getRole(USER_ID)).thenReturn(MemberRole.USER);
        when(winnerRepository.findById(WINNER_ID)).thenReturn(Optional.of(winner));
        when(winnerManagementRepository.findByWinnerId(WINNER_ID)).thenReturn(Optional.of(management));
        when(winnerStatusHistoryRepository.findByWinnerManagementIdOrderByCreatedAtAscIdAsc(300L))
                .thenReturn(List.of(history));

        List<WinnerStatusHistoryResult> results = service.getHistory(WINNER_ID, USER_ID);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.historyId()).isEqualTo(900L);
            assertThat(result.beforeStatus()).isEqualTo(WinnerManagementStatus.SELECTED);
            assertThat(result.afterStatus()).isEqualTo(WinnerManagementStatus.DECLINED);
            assertThat(result.reason()).isNull();
            assertThat(result.changedBy()).isEqualTo(USER_ID);
            assertThat(result.changedAt()).isEqualTo(Instant.parse("2026-09-21T01:00:00Z"));
        });
    }

    /** ADMIN은 다른 사용자가 소유한 Winner의 상태 이력도 조회할 수 있다. */
    @Test
    void ADMIN은_모든_Winner_상태_이력을_조회한다() {
        Winner winner = mock(Winner.class);
        WinnerManagement management = management(300L);
        when(memberQueryService.getRole(ADMIN_ID)).thenReturn(MemberRole.ADMIN);
        when(winnerRepository.findById(WINNER_ID)).thenReturn(Optional.of(winner));
        when(winnerManagementRepository.findByWinnerId(WINNER_ID)).thenReturn(Optional.of(management));
        when(winnerStatusHistoryRepository.findByWinnerManagementIdOrderByCreatedAtAscIdAsc(300L))
                .thenReturn(List.of());

        List<WinnerStatusHistoryResult> results = service.getHistory(WINNER_ID, ADMIN_ID);

        assertThat(results).isEmpty();
        verify(winnerStatusHistoryRepository).findByWinnerManagementIdOrderByCreatedAtAscIdAsc(300L);
    }

    /** 존재하지 않는 호출자 Member는 Winner 정보를 조회하기 전에 차단한다. */
    @Test
    void 존재하지_않는_Member는_Winner를_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .when(memberQueryService).getRole(USER_ID);

        assertThatThrownBy(() -> service.getHistory(WINNER_ID, USER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(winnerRepository, winnerManagementRepository, winnerStatusHistoryRepository);
    }

    /** USER는 다른 사용자가 소유한 Winner의 상태 이력을 조회할 수 없다. */
    @Test
    void USER가_다른_사용자의_Winner를_조회하면_FORBIDDEN이다() {
        Winner otherUserWinner = winner(3L);
        when(memberQueryService.getRole(USER_ID)).thenReturn(MemberRole.USER);
        when(winnerRepository.findById(WINNER_ID)).thenReturn(Optional.of(otherUserWinner));

        assertThatThrownBy(() -> service.getHistory(WINNER_ID, USER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);

        verifyNoInteractions(winnerManagementRepository, winnerStatusHistoryRepository);
    }

    /** 존재하지 않는 Winner는 운영 정보와 이력을 조회하지 않는다. */
    @Test
    void 존재하지_않는_Winner는_WINNER_NOT_FOUND이다() {
        when(memberQueryService.getRole(ADMIN_ID)).thenReturn(MemberRole.ADMIN);
        when(winnerRepository.findById(WINNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHistory(WINNER_ID, ADMIN_ID))
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.WINNER_NOT_FOUND);

        verifyNoInteractions(winnerManagementRepository, winnerStatusHistoryRepository);
    }

    /** Winner에 연결된 운영 정보가 없으면 이력을 조회하지 않는다. */
    @Test
    void 운영_정보가_없으면_WINNER_MANAGEMENT_NOT_FOUND이다() {
        Winner winner = mock(Winner.class);
        when(memberQueryService.getRole(ADMIN_ID)).thenReturn(MemberRole.ADMIN);
        when(winnerRepository.findById(WINNER_ID)).thenReturn(Optional.of(winner));
        when(winnerManagementRepository.findByWinnerId(WINNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHistory(WINNER_ID, ADMIN_ID))
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.WINNER_MANAGEMENT_NOT_FOUND);

        verifyNoInteractions(winnerStatusHistoryRepository);
    }

    /** Winner를 지정한 소유자 정보로 반환하는 테스트 double을 만든다. */
    private Winner winner(Long memberId) {
        Winner winner = mock(Winner.class);
        when(winner.getMemberId()).thenReturn(memberId);
        return winner;
    }

    /** WinnerManagement 식별자를 반환하는 테스트 double을 만든다. */
    private WinnerManagement management(Long managementId) {
        WinnerManagement management = mock(WinnerManagement.class);
        when(management.getId()).thenReturn(managementId);
        return management;
    }

    /** 상태 이력 응답 변환을 검증할 테스트 double을 만든다. */
    private WinnerStatusHistory history(
            Long historyId,
            WinnerManagementStatus previousStatus,
            WinnerManagementStatus status,
            String reason,
            Long changedBy
    ) {
        WinnerStatusHistory history = mock(WinnerStatusHistory.class);
        when(history.getId()).thenReturn(historyId);
        when(history.getPreviousStatus()).thenReturn(previousStatus);
        when(history.getStatus()).thenReturn(status);
        when(history.getReason()).thenReturn(reason);
        when(history.getChangedBy()).thenReturn(changedBy);
        when(history.getCreatedAt()).thenReturn(Instant.parse("2026-09-21T01:00:00Z"));
        return history;
    }
}
