package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.application.RedrawDrawingExecutionResult;
import kr.co.cking.drawing.application.RedrawDrawingExecutionService;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestVacancy;
import kr.co.cking.redraw.repository.RedrawExecutionHistoryRepository;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import kr.co.cking.redraw.repository.RedrawRequestVacancyRepository;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** REDRAW 실행 전 상태 검증과 시스템3 위임 순서를 검증한다. */
@ExtendWith(MockitoExtension.class)
class RedrawRequestExecutionTransactionServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final long REDRAW_REQUEST_ID = 10L;

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private RedrawRequestRepository redrawRequestRepository;

    @Mock
    private RedrawRequestVacancyRepository vacancyRepository;

    @Mock
    private RedrawExecutionHistoryRepository historyRepository;

    @Mock
    private RedrawDrawingExecutionService redrawDrawingExecutionService;

    private RedrawRequestExecutionTransactionService service;

    @BeforeEach
    void setUp() {
        service = new RedrawRequestExecutionTransactionService(memberQueryService, redrawRequestRepository,
                vacancyRepository, historyRepository, redrawDrawingExecutionService);
    }

    /** 승인 전 요청은 결원 재검증이나 시스템3 실행 전에 즉시 상태 오류로 차단한다. */
    @Test
    void APPROVED와_PENDING이_아닌_요청은_시스템3_호출_전_INVALID_STATE다() {
        RedrawRequest request = RedrawRequest.requested(20L, 30L, 1, "재추첨 사유", "key-10", ADMIN_ID);
        when(redrawRequestRepository.findByIdForUpdate(REDRAW_REQUEST_ID)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.execute(ADMIN_ID, REDRAW_REQUEST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RedrawErrorCode.INVALID_STATE);

        verify(memberQueryService).validateAdmin(ADMIN_ID);
        verify(redrawRequestRepository).findByIdForUpdate(REDRAW_REQUEST_ID);
        verifyNoInteractions(vacancyRepository, historyRepository, redrawDrawingExecutionService);
    }

    /** 시스템3이 REDRAW Drawing을 만들면 실행 상태·이력을 EXECUTED로 함께 남긴다. */
    @Test
    void 정상_실행은_EXECUTED_상태와_이력을_저장한다() {
        RedrawRequest request = approvedRequest();
        when(redrawRequestRepository.findByIdForUpdate(REDRAW_REQUEST_ID)).thenReturn(Optional.of(request));
        when(vacancyRepository.findAllByRedrawRequestIdOrderByIdAsc(REDRAW_REQUEST_ID))
                .thenReturn(java.util.List.of(mock(RedrawRequestVacancy.class)));
        when(redrawDrawingExecutionService.execute(REDRAW_REQUEST_ID, ADMIN_ID, 30L, 1))
                .thenReturn(RedrawDrawingExecutionResult.executed(40L));

        RedrawRequestExecutionResult result = service.execute(ADMIN_ID, REDRAW_REQUEST_ID);

        assertThat(result).isEqualTo(new RedrawRequestExecutionResult(
                REDRAW_REQUEST_ID, RedrawExecutionStatus.EXECUTED, 40L));
        assertThat(request.getExecutionStatus()).isEqualTo(RedrawExecutionStatus.EXECUTED);
        assertThat(request.getCompletedAt()).isNotNull();
        verify(historyRepository).save(any());
    }

    /** 시스템3의 후보 부족은 예외가 아닌 INSUFFICIENT_CANDIDATES 종결 결과로 기록한다. */
    @Test
    void 후보_부족은_INSUFFICIENT_CANDIDATES_상태와_이력을_저장한다() {
        RedrawRequest request = approvedRequest();
        when(redrawRequestRepository.findByIdForUpdate(REDRAW_REQUEST_ID)).thenReturn(Optional.of(request));
        when(vacancyRepository.findAllByRedrawRequestIdOrderByIdAsc(REDRAW_REQUEST_ID))
                .thenReturn(java.util.List.of(mock(RedrawRequestVacancy.class)));
        when(redrawDrawingExecutionService.execute(REDRAW_REQUEST_ID, ADMIN_ID, 30L, 1))
                .thenReturn(RedrawDrawingExecutionResult.noCandidates());

        RedrawRequestExecutionResult result = service.execute(ADMIN_ID, REDRAW_REQUEST_ID);

        assertThat(result).isEqualTo(new RedrawRequestExecutionResult(
                REDRAW_REQUEST_ID, RedrawExecutionStatus.INSUFFICIENT_CANDIDATES, null));
        assertThat(request.getExecutionStatus()).isEqualTo(RedrawExecutionStatus.INSUFFICIENT_CANDIDATES);
        assertThat(request.getCompletedAt()).isNotNull();
        verify(historyRepository).save(any());
    }

    /** 시스템3 내부의 업무 예외는 실행 전 요청 검증 오류와 구분해 호출자에게 전달한다. */
    @Test
    void 시스템3_업무_예외는_실행_실패_표식_예외로_전환한다() {
        RedrawRequest request = approvedRequest();
        BusinessException snapshotFailure = new BusinessException(SnapshotErrorCode.SNAPSHOT_HASH_MISMATCH);
        when(redrawRequestRepository.findByIdForUpdate(REDRAW_REQUEST_ID)).thenReturn(Optional.of(request));
        when(vacancyRepository.findAllByRedrawRequestIdOrderByIdAsc(REDRAW_REQUEST_ID))
                .thenReturn(java.util.List.of(mock(RedrawRequestVacancy.class)));
        when(redrawDrawingExecutionService.execute(REDRAW_REQUEST_ID, ADMIN_ID, 30L, 1))
                .thenThrow(snapshotFailure);

        assertThatThrownBy(() -> service.execute(ADMIN_ID, REDRAW_REQUEST_ID))
                .isInstanceOf(RedrawDrawingExecutionBusinessFailureException.class)
                .satisfies(exception -> assertThat(exception.getCause()).isSameAs(snapshotFailure));

        verifyNoInteractions(historyRepository);
    }

    /** 고정 결원 수와 저장된 결원 수가 다르면 시스템3 실행 전에 차단한다. */
    @Test
    void 결원_수가_불일치하면_시스템3_호출_전_INVALID_STATE다() {
        RedrawRequest request = approvedRequest();
        when(redrawRequestRepository.findByIdForUpdate(REDRAW_REQUEST_ID)).thenReturn(Optional.of(request));
        when(vacancyRepository.findAllByRedrawRequestIdOrderByIdAsc(REDRAW_REQUEST_ID))
                .thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.execute(ADMIN_ID, REDRAW_REQUEST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RedrawErrorCode.INVALID_STATE);

        verifyNoInteractions(historyRepository, redrawDrawingExecutionService);
    }

    /** 실행 가능한 승인·대기 상태의 결원 한 건 요청을 만든다. */
    private RedrawRequest approvedRequest() {
        RedrawRequest request = RedrawRequest.requested(20L, 30L, 1, "재추첨 사유", "key-10", ADMIN_ID);
        request.approve(ADMIN_ID);
        return request;
    }
}
