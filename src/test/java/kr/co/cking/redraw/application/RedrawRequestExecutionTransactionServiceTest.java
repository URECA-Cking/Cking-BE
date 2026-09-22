package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.application.RedrawDrawingExecutionService;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.repository.RedrawExecutionHistoryRepository;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import kr.co.cking.redraw.repository.RedrawRequestVacancyRepository;
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
}
