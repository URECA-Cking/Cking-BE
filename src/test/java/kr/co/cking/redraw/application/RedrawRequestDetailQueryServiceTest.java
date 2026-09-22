package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestStatus;
import kr.co.cking.redraw.domain.RedrawRequestVacancy;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import kr.co.cking.redraw.repository.RedrawRequestVacancyRepository;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 관리자 RedrawRequest 상세 조회의 권한·결원·실행 상태 조합을 검증한다. */
@ExtendWith(MockitoExtension.class)
class RedrawRequestDetailQueryServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long REDRAW_REQUEST_ID = 30L;

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private RedrawRequestRepository redrawRequestRepository;

    @Mock
    private RedrawRequestVacancyRepository redrawRequestVacancyRepository;

    @Mock
    private WinnerRepository winnerRepository;

    @Mock
    private DrawingRepository drawingRepository;

    private RedrawRequestDetailQueryService service;

    /** 조회 Service에 필요한 협력 객체를 매 테스트 전에 조립한다. */
    @BeforeEach
    void setUp() {
        service = new RedrawRequestDetailQueryService(
                memberQueryService,
                redrawRequestRepository,
                redrawRequestVacancyRepository,
                winnerRepository,
                drawingRepository
        );
    }

    /** 관리자는 요청 시점에 고정한 결원, 심사 정보 및 실행된 REDRAW Drawing을 조회한다. */
    @Test
    void 관리자는_고정_결원과_실행_정보를_포함한_상세를_조회한다() {
        RedrawRequest request = request(RedrawRequestStatus.APPROVED, RedrawExecutionStatus.EXECUTED);
        RedrawRequestVacancy vacancy = vacancy(100L);
        Winner winner = winner(100L, 3L, 1);
        Drawing redrawDrawing = mock(Drawing.class);
        when(redrawDrawing.getId()).thenReturn(40L);
        when(redrawRequestRepository.findById(REDRAW_REQUEST_ID)).thenReturn(Optional.of(request));
        when(redrawRequestVacancyRepository.findAllByRedrawRequestIdOrderByIdAsc(REDRAW_REQUEST_ID))
                .thenReturn(List.of(vacancy));
        when(winnerRepository.findAllById(List.of(100L))).thenReturn(List.of(winner));
        when(memberQueryService.findMemberInfosByIds(List.of(3L))).thenReturn(Map.of(
                3L, new MemberInfo(3L, "당첨자", "010-0000-0003", "winner@example.com")
        ));
        when(drawingRepository.findByRedrawRequestId(REDRAW_REQUEST_ID)).thenReturn(Optional.of(redrawDrawing));

        RedrawRequestDetailResult result = service.getDetail(REDRAW_REQUEST_ID, ADMIN_ID);

        verify(memberQueryService).validateAdmin(ADMIN_ID);
        assertThat(result.redrawRequestId()).isEqualTo(REDRAW_REQUEST_ID);
        assertThat(result.originalDrawingId()).isEqualTo(20L);
        assertThat(result.redrawDrawingId()).isEqualTo(40L);
        assertThat(result.vacancyCount()).isEqualTo(1);
        assertThat(result.vacancyWinners()).containsExactly(
                new RedrawVacancyWinnerResult(100L, 3L, "당첨자", 1)
        );
        assertThat(result.status()).isEqualTo(RedrawRequestStatus.APPROVED);
        assertThat(result.executionStatus()).isEqualTo(RedrawExecutionStatus.EXECUTED);
        assertThat(result.requestedBy()).isEqualTo(ADMIN_ID);
        assertThat(result.reviewedBy()).isEqualTo(2L);
        assertThat(result.rejectReason()).isNull();
    }

    /** 후보 부족으로 REDRAW Drawing이 생기지 않은 요청은 redrawDrawingId를 null로 반환한다. */
    @Test
    void 후보_부족_요청은_REDRAW_Drawing_ID를_null로_반환한다() {
        RedrawRequest request = request(RedrawRequestStatus.APPROVED, RedrawExecutionStatus.INSUFFICIENT_CANDIDATES);
        RedrawRequestVacancy vacancy = vacancy(100L);
        Winner winner = winner(100L, 3L, 1);
        when(redrawRequestRepository.findById(REDRAW_REQUEST_ID)).thenReturn(Optional.of(request));
        when(redrawRequestVacancyRepository.findAllByRedrawRequestIdOrderByIdAsc(REDRAW_REQUEST_ID))
                .thenReturn(List.of(vacancy));
        when(winnerRepository.findAllById(List.of(100L))).thenReturn(List.of(winner));
        when(memberQueryService.findMemberInfosByIds(List.of(3L))).thenReturn(Map.of(
                3L, new MemberInfo(3L, "당첨자", "010-0000-0003", "winner@example.com")
        ));
        when(drawingRepository.findByRedrawRequestId(REDRAW_REQUEST_ID)).thenReturn(Optional.empty());

        RedrawRequestDetailResult result = service.getDetail(REDRAW_REQUEST_ID, ADMIN_ID);

        assertThat(result.redrawDrawingId()).isNull();
        assertThat(result.executionStatus()).isEqualTo(RedrawExecutionStatus.INSUFFICIENT_CANDIDATES);
        assertThat(result.vacancyWinners()).hasSize(1);
    }

    /** 없는 요청은 Redraw 도메인의 요청 없음 오류로 반환한다. */
    @Test
    void 없는_RedrawRequest는_REDRAW_REQUEST_NOT_FOUND다() {
        when(redrawRequestRepository.findById(REDRAW_REQUEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(REDRAW_REQUEST_ID, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RedrawErrorCode.REDRAW_REQUEST_NOT_FOUND);
        verifyNoInteractions(redrawRequestVacancyRepository, winnerRepository, drawingRepository);
    }

    /** 관리자 검증에 실패하면 요청과 결원 데이터를 읽지 않는다. */
    @Test
    void 관리자_검증에_실패하면_상세_데이터를_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.getDetail(REDRAW_REQUEST_ID, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        verifyNoInteractions(redrawRequestRepository, redrawRequestVacancyRepository, winnerRepository, drawingRepository);
    }

    /** 요청 상세 테스트에 사용할 심사·실행 상태의 RedrawRequest Mock을 만든다. */
    private RedrawRequest request(RedrawRequestStatus status, RedrawExecutionStatus executionStatus) {
        RedrawRequest request = mock(RedrawRequest.class);
        when(request.getId()).thenReturn(REDRAW_REQUEST_ID);
        when(request.getEventId()).thenReturn(10L);
        when(request.getOriginalDrawingId()).thenReturn(20L);
        when(request.getVacancyCount()).thenReturn(1);
        when(request.getStatus()).thenReturn(status);
        when(request.getExecutionStatus()).thenReturn(executionStatus);
        when(request.getReason()).thenReturn("당첨자 포기에 따른 재추첨");
        when(request.getRequestedBy()).thenReturn(ADMIN_ID);
        when(request.getRequestedAt()).thenReturn(Instant.parse("2026-09-20T00:00:00Z"));
        when(request.getReviewedBy()).thenReturn(2L);
        when(request.getReviewedAt()).thenReturn(Instant.parse("2026-09-20T01:00:00Z"));
        when(request.getRejectReason()).thenReturn(null);
        return request;
    }

    /** 요청이 고정한 특정 Winner ID를 가리키는 결원 Mock을 만든다. */
    private RedrawRequestVacancy vacancy(Long winnerId) {
        RedrawRequestVacancy vacancy = mock(RedrawRequestVacancy.class);
        when(vacancy.getWinnerId()).thenReturn(winnerId);
        return vacancy;
    }

    /** 결원 목록 응답에 사용할 원본 Drawing Winner Mock을 만든다. */
    private Winner winner(Long winnerId, Long memberId, int rankInDrawing) {
        Winner winner = mock(Winner.class);
        when(winner.getId()).thenReturn(winnerId);
        when(winner.getMemberId()).thenReturn(memberId);
        when(winner.getRankInDrawing()).thenReturn(rankInDrawing);
        return winner;
    }
}
