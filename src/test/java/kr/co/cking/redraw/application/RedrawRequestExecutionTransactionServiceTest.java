package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.application.RedrawDrawingExecutionResult;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestVacancy;
import kr.co.cking.redraw.repository.RedrawExecutionHistoryRepository;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import kr.co.cking.redraw.repository.RedrawRequestVacancyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedrawRequestExecutionTransactionServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final long REQUEST_ID = 10L;

    @Mock MemberQueryService memberQueryService;
    @Mock RedrawRequestRepository requestRepository;
    @Mock RedrawRequestVacancyRepository vacancyRepository;
    @Mock RedrawExecutionHistoryRepository historyRepository;

    private RedrawRequestExecutionTransactionService service;

    @BeforeEach
    void setUp() {
        service = new RedrawRequestExecutionTransactionService(
                memberQueryService, requestRepository, vacancyRepository, historyRepository);
    }

    @Test
    void 승인_대기_상태가_아니면_실행_Command를_만들지_않는다() {
        RedrawRequest request = RedrawRequest.requested(20L, 30L, 1, "사유", "key", ADMIN_ID);
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.prepare(ADMIN_ID, REQUEST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RedrawErrorCode.INVALID_STATE);

        verify(memberQueryService).validateAdmin(ADMIN_ID);
        verifyNoInteractions(vacancyRepository, historyRepository);
    }

    @Test
    void 승인된_요청과_고정_결원을_실행_Command로_반환한다() {
        RedrawRequest request = approvedRequest();
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(vacancyRepository.findAllByRedrawRequestIdOrderByIdAsc(REQUEST_ID))
                .thenReturn(List.of(mock(RedrawRequestVacancy.class)));

        assertThat(service.prepare(ADMIN_ID, REQUEST_ID)).isEqualTo(
                new RedrawExecutionCommand(REQUEST_ID, ADMIN_ID, 30L, 1));
    }

    @Test
    void 후보_부족_결과를_요청과_이력에_저장한다() {
        RedrawRequest request = approvedRequest();
        RedrawExecutionCommand command = new RedrawExecutionCommand(REQUEST_ID, ADMIN_ID, 30L, 1);
        when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));

        RedrawRequestExecutionResult result = service.complete(
                command, RedrawDrawingExecutionResult.noCandidates());

        assertThat(result.executionStatus()).isEqualTo(RedrawExecutionStatus.INSUFFICIENT_CANDIDATES);
        assertThat(request.getExecutionStatus()).isEqualTo(RedrawExecutionStatus.INSUFFICIENT_CANDIDATES);
        verify(historyRepository).save(any());
    }

    @Test
    void 시스템3이_이미_EXECUTED로_확정한_결과는_이력을_중복_저장하지_않는다() {
        RedrawRequest request = approvedRequest();
        request.completeExecution(java.time.Instant.parse("2026-09-22T00:00:00Z"));
        RedrawExecutionCommand command = new RedrawExecutionCommand(REQUEST_ID, ADMIN_ID, 30L, 1);
        when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));

        RedrawRequestExecutionResult result = service.complete(
                command, RedrawDrawingExecutionResult.executed(40L));

        assertThat(result).isEqualTo(new RedrawRequestExecutionResult(
                REQUEST_ID, RedrawExecutionStatus.EXECUTED, 40L));
        verifyNoInteractions(historyRepository);
    }

    @Test
    void FAILED_Drawing_ID를_그대로_응답한다() {
        RedrawRequest request = approvedRequest();
        request.failExecution(java.time.Instant.parse("2026-09-22T00:00:00Z"));
        RedrawExecutionCommand command = new RedrawExecutionCommand(REQUEST_ID, ADMIN_ID, 30L, 1);
        when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));

        assertThat(service.complete(command, RedrawDrawingExecutionResult.failed(40L)))
                .isEqualTo(new RedrawRequestExecutionResult(
                        REQUEST_ID, RedrawExecutionStatus.FAILED, 40L));
    }

    @Test
    void 고정_결원_수가_다르면_실행_전에_차단한다() {
        RedrawRequest request = approvedRequest();
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(vacancyRepository.findAllByRedrawRequestIdOrderByIdAsc(REQUEST_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.prepare(ADMIN_ID, REQUEST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RedrawErrorCode.INVALID_STATE);
    }

    private RedrawRequest approvedRequest() {
        RedrawRequest request = RedrawRequest.requested(20L, 30L, 1, "사유", "key", ADMIN_ID);
        request.approve(ADMIN_ID);
        return request;
    }
}
