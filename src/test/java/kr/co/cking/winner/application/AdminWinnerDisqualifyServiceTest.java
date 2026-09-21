package kr.co.cking.winner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
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

/** 관리자 Winner 자격 박탈 유스케이스의 권한·사유·상태·이력 처리를 검증한다. */
@ExtendWith(MockitoExtension.class)
class AdminWinnerDisqualifyServiceTest {

    private static final long WINNER_ID = 100L;
    private static final long ADMIN_ID = 1L;
    private static final String REASON = "이벤트 참여 조건을 충족하지 않았습니다.";

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private WinnerRepository winnerRepository;

    @Mock
    private WinnerManagementRepository winnerManagementRepository;

    @Mock
    private WinnerStatusHistoryRepository winnerStatusHistoryRepository;

    private AdminWinnerDisqualifyService service;

    /** 각 테스트가 독립된 Mock 의존성을 사용하는 Service를 만든다. */
    @BeforeEach
    void setUp() {
        service = new AdminWinnerDisqualifyService(
                memberQueryService,
                winnerRepository,
                winnerManagementRepository,
                winnerStatusHistoryRepository
        );
    }

    /** 관리자는 SELECTED Winner를 DISQUALIFIED로 전이하고 정규화한 사유·주체를 이력에 남긴다. */
    @Test
    void 관리자_자격_박탈은_SELECTED_Winner를_DISQUALIFIED로_변경하고_변경_이력을_저장한다() {
        WinnerManagement management = management();
        when(winnerRepository.existsById(WINNER_ID)).thenReturn(true);
        when(winnerManagementRepository.findByWinnerIdForUpdate(WINNER_ID)).thenReturn(Optional.of(management));

        service.disqualify(WINNER_ID, ADMIN_ID, "  " + REASON + "  ");

        assertThat(management.getStatus()).isEqualTo(WinnerManagementStatus.DISQUALIFIED);
        ArgumentCaptor<WinnerStatusHistory> historyCaptor = ArgumentCaptor.forClass(WinnerStatusHistory.class);
        verify(winnerStatusHistoryRepository).save(historyCaptor.capture());
        WinnerStatusHistory history = historyCaptor.getValue();
        assertThat(history.getWinnerManagementId()).isEqualTo(300L);
        assertThat(history.getStatus()).isEqualTo(WinnerManagementStatus.DISQUALIFIED);
        assertThat(history.getReason()).isEqualTo(REASON);
        assertThat(history.getChangedBy()).isEqualTo(ADMIN_ID);
    }

    /** 존재하지 않는 관리자 Member면 Winner 조회와 상태 변경을 시도하지 않는다. */
    @Test
    void 존재하지_않는_관리자_Member면_Winner를_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.disqualify(WINNER_ID, ADMIN_ID, REASON))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(winnerRepository, winnerManagementRepository, winnerStatusHistoryRepository);
    }

    /** 관리자 역할이 아닌 Member면 Winner 조회와 상태 변경을 시도하지 않는다. */
    @Test
    void 관리자가_아닌_Member면_Winner를_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.disqualify(WINNER_ID, ADMIN_ID, REASON))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);

        verifyNoInteractions(winnerRepository, winnerManagementRepository, winnerStatusHistoryRepository);
    }

    /** 공백 또는 500자를 넘는 사유는 Winner 조회와 상태 변경 전에 차단한다. */
    @Test
    void 유효하지_않은_자격_박탈_사유는_입력_검증_오류다() {
        assertThatThrownBy(() -> service.disqualify(WINNER_ID, ADMIN_ID, " "))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.disqualify(WINNER_ID, ADMIN_ID, "가".repeat(501)))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(winnerRepository, winnerManagementRepository, winnerStatusHistoryRepository);
    }

    /** 존재하지 않는 Winner는 운영 정보 잠금과 변경 이력 생성을 시도하지 않는다. */
    @Test
    void 존재하지_않는_Winner는_WINNER_NOT_FOUND이다() {
        when(winnerRepository.existsById(WINNER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.disqualify(WINNER_ID, ADMIN_ID, REASON))
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.WINNER_NOT_FOUND);

        verifyNoInteractions(winnerManagementRepository, winnerStatusHistoryRepository);
    }

    /** WinnerManagement가 없으면 상태 변경 이력을 남기지 않는다. */
    @Test
    void 운영_정보가_없으면_WINNER_MANAGEMENT_NOT_FOUND이다() {
        when(winnerRepository.existsById(WINNER_ID)).thenReturn(true);
        when(winnerManagementRepository.findByWinnerIdForUpdate(WINNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.disqualify(WINNER_ID, ADMIN_ID, REASON))
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.WINNER_MANAGEMENT_NOT_FOUND);

        verifyNoInteractions(winnerStatusHistoryRepository);
    }

    /** 종결 상태에서는 재변경과 중복 이력 추가를 차단한다. */
    @Test
    void DISQUALIFIED_종결_상태에서는_재변경과_이력_추가를_차단한다() {
        WinnerManagement management = management();
        management.disqualify();
        when(winnerRepository.existsById(WINNER_ID)).thenReturn(true);
        when(winnerManagementRepository.findByWinnerIdForUpdate(WINNER_ID)).thenReturn(Optional.of(management));

        assertThatThrownBy(() -> service.disqualify(WINNER_ID, ADMIN_ID, REASON))
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.INVALID_STATE);

        verifyNoInteractions(winnerStatusHistoryRepository);
    }

    /** 테스트용 SELECTED 운영 정보를 만들고 식별자를 설정한다. */
    private WinnerManagement management() {
        WinnerManagement management = WinnerManagement.selected(WINNER_ID);
        ReflectionTestUtils.setField(management, "id", 300L);
        return management;
    }
}
