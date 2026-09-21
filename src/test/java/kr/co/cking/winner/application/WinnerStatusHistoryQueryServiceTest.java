package kr.co.cking.winner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.test.util.ReflectionTestUtils;

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
        Winner winner = winner(3L);
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
        Winner winner = winner(USER_ID);
        when(memberQueryService.getRole(ADMIN_ID)).thenReturn(MemberRole.ADMIN);
        when(winnerRepository.findById(WINNER_ID)).thenReturn(Optional.of(winner));
        when(winnerManagementRepository.findByWinnerId(WINNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHistory(WINNER_ID, ADMIN_ID))
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.WINNER_MANAGEMENT_NOT_FOUND);

        verifyNoInteractions(winnerStatusHistoryRepository);
    }

    /** 테스트용 불변 Winner를 만들고 식별자를 설정한다. */
    private Winner winner(Long memberId) {
        Winner winner = Winner.create(10L, 20L, memberId, 1, 3L);
        ReflectionTestUtils.setField(winner, "id", WINNER_ID);
        return winner;
    }

    /** 테스트용 SELECTED 운영 정보를 만들고 식별자를 설정한다. */
    private WinnerManagement management(Long managementId) {
        WinnerManagement management = WinnerManagement.selected(WINNER_ID);
        ReflectionTestUtils.setField(management, "id", managementId);
        return management;
    }

    /** 테스트용 상태 이력을 만들고 조회 응답에 필요한 식별자·시각을 설정한다. */
    private WinnerStatusHistory history(
            Long historyId,
            WinnerManagementStatus previousStatus,
            WinnerManagementStatus status,
            String reason,
            Long changedBy
    ) {
        WinnerStatusHistory history = WinnerStatusHistory.create(
                300L, previousStatus, status, reason, changedBy
        );
        ReflectionTestUtils.setField(history, "id", historyId);
        ReflectionTestUtils.setField(history, "createdAt", Instant.parse("2026-09-21T01:00:00Z"));
        return history;
    }
}
