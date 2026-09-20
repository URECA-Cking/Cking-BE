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

/** 관리자 Winner 수령 완료 유스케이스의 권한·상태·이력 처리를 검증한다. */
@ExtendWith(MockitoExtension.class)
class AdminWinnerReceiveServiceTest {

    private static final long WINNER_ID = 100L;
    private static final long ADMIN_ID = 1L;

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private WinnerRepository winnerRepository;

    @Mock
    private WinnerManagementRepository winnerManagementRepository;

    @Mock
    private WinnerStatusHistoryRepository winnerStatusHistoryRepository;

    private AdminWinnerReceiveService service;

    /** 각 테스트가 독립된 Mock 의존성을 사용하는 Service를 만든다. */
    @BeforeEach
    void setUp() {
        service = new AdminWinnerReceiveService(
                memberQueryService,
                winnerRepository,
                winnerManagementRepository,
                winnerStatusHistoryRepository
        );
    }

    @Test
    /** 관리자는 SELECTED Winner를 RECEIVED로 전이하고 자신을 변경 주체로 이력에 남긴다. */
    void 관리자_수령_완료는_SELECTED_Winner를_RECEIVED로_변경하고_변경_이력을_저장한다() {
        WinnerManagement management = management();
        when(winnerRepository.existsById(WINNER_ID)).thenReturn(true);
        when(winnerManagementRepository.findByWinnerIdForUpdate(WINNER_ID)).thenReturn(Optional.of(management));

        service.receive(WINNER_ID, ADMIN_ID);

        assertThat(management.getStatus()).isEqualTo(WinnerManagementStatus.RECEIVED);
        ArgumentCaptor<WinnerStatusHistory> historyCaptor = ArgumentCaptor.forClass(WinnerStatusHistory.class);
        verify(winnerStatusHistoryRepository).save(historyCaptor.capture());
        WinnerStatusHistory history = historyCaptor.getValue();
        assertThat(history.getWinnerManagementId()).isEqualTo(300L);
        assertThat(history.getStatus()).isEqualTo(WinnerManagementStatus.RECEIVED);
        assertThat(history.getReason()).isNull();
        assertThat(history.getChangedBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    /** 존재하지 않는 관리자 Member면 Winner 조회와 상태 변경을 시도하지 않는다. */
    void 존재하지_않는_관리자_Member면_Winner를_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.receive(WINNER_ID, ADMIN_ID))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(winnerRepository, winnerManagementRepository, winnerStatusHistoryRepository);
    }

    @Test
    /** 관리자 역할이 아닌 Member면 Winner 조회와 상태 변경을 시도하지 않는다. */
    void 관리자가_아닌_Member면_Winner를_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.receive(WINNER_ID, ADMIN_ID))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);

        verifyNoInteractions(winnerRepository, winnerManagementRepository, winnerStatusHistoryRepository);
    }

    @Test
    /** 존재하지 않는 Winner는 운영 정보 잠금과 변경 이력 생성을 시도하지 않는다. */
    void 존재하지_않는_Winner는_WINNER_NOT_FOUND이다() {
        when(winnerRepository.existsById(WINNER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.receive(WINNER_ID, ADMIN_ID))
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.WINNER_NOT_FOUND);

        verifyNoInteractions(winnerManagementRepository, winnerStatusHistoryRepository);
    }

    @Test
    /** WinnerManagement가 없으면 상태 변경 이력을 남기지 않는다. */
    void 운영_정보가_없으면_WINNER_MANAGEMENT_NOT_FOUND이다() {
        when(winnerRepository.existsById(WINNER_ID)).thenReturn(true);
        when(winnerManagementRepository.findByWinnerIdForUpdate(WINNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(WINNER_ID, ADMIN_ID))
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.WINNER_MANAGEMENT_NOT_FOUND);

        verifyNoInteractions(winnerStatusHistoryRepository);
    }

    @Test
    /** 이미 수령 완료된 Winner는 재변경과 중복 이력 생성을 차단한다. */
    void RECEIVED_종결_상태에서는_재변경과_이력_추가를_차단한다() {
        WinnerManagement management = management();
        management.receive();
        when(winnerRepository.existsById(WINNER_ID)).thenReturn(true);
        when(winnerManagementRepository.findByWinnerIdForUpdate(WINNER_ID)).thenReturn(Optional.of(management));

        assertThatThrownBy(() -> service.receive(WINNER_ID, ADMIN_ID))
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
