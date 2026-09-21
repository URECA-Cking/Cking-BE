package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestStatus;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RedrawRequest 생성의 권한·입력·멱등 재시도 계약을 검증한다. */
@ExtendWith(MockitoExtension.class)
class RedrawRequestCreateServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final long EVENT_ID = 10L;
    private static final String REASON = "당첨자 포기에 따른 재추첨이 필요합니다.";
    private static final String IDEMPOTENCY_KEY = "d2719c4a-1f9b-4dc4-a656-9a4bb37d8e70";

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private RedrawRequestRepository redrawRequestRepository;

    @Mock
    private RedrawRequestCreationPersistenceService persistenceService;

    private RedrawRequestCreateService service;

    /** 각 테스트가 독립된 Mock 의존성으로 Service를 구성한다. */
    @BeforeEach
    void setUp() {
        service = new RedrawRequestCreateService(memberQueryService, redrawRequestRepository, persistenceService);
    }

    /** 새 키면 사유를 정규화해 결원 확정 저장을 위임하고 생성 결과를 반환한다. */
    @Test
    void 새_idempotencyKey면_정규화한_사유로_RedrawRequest를_생성한다() {
        RedrawRequest request = request(100L, REASON);
        when(redrawRequestRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(persistenceService.create(new RedrawRequestCreateCommand(ADMIN_ID, EVENT_ID, REASON, IDEMPOTENCY_KEY)))
                .thenReturn(request);

        RedrawRequestCreateResult result = service.create(command("  " + REASON + "  "));

        assertThat(result.redrawRequestId()).isEqualTo(100L);
        assertThat(result.eventId()).isEqualTo(EVENT_ID);
        assertThat(result.originalDrawingId()).isEqualTo(20L);
        assertThat(result.vacancyCount()).isEqualTo(2);
        assertThat(result.created()).isTrue();
        verify(memberQueryService).validateAdmin(ADMIN_ID);
        verify(persistenceService).create(new RedrawRequestCreateCommand(ADMIN_ID, EVENT_ID, REASON, IDEMPOTENCY_KEY));
    }

    /** 같은 키와 같은 본문은 새 결원 계산 없이 기존 요청을 멱등 재사용한다. */
    @Test
    void 같은_키와_같은_본문은_기존_RedrawRequest를_반환한다() {
        RedrawRequest existing = request(100L, REASON);
        when(redrawRequestRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        RedrawRequestCreateResult result = service.create(command(REASON));

        assertThat(result.redrawRequestId()).isEqualTo(100L);
        assertThat(result.created()).isFalse();
        verifyNoInteractions(persistenceService);
    }

    /** 같은 키에 Event·요청자·사유 중 하나가 다르면 멱등성 충돌로 차단한다. */
    @Test
    void 같은_키에_다른_사유가_전달되면_IDEMPOTENCY_CONFLICT다() {
        RedrawRequest existing = request(100L, REASON);
        when(redrawRequestRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(command("다른 사유")))
                .hasFieldOrPropertyWithValue("errorCode", RedrawErrorCode.IDEMPOTENCY_CONFLICT);

        verifyNoInteractions(persistenceService);
    }

    /** 존재하지 않는 Member는 멱등 조회나 결원 계산 전에 차단한다. */
    @Test
    void 존재하지_않는_관리자_Member는_생성을_시도하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.create(command(REASON)))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(redrawRequestRepository, persistenceService);
    }

    /** ADMIN이 아닌 Member는 멱등 조회나 결원 계산 전에 차단한다. */
    @Test
    void ADMIN이_아닌_Member는_생성을_시도하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.create(command(REASON)))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);

        verifyNoInteractions(redrawRequestRepository, persistenceService);
    }

    /** 공백 사유·잘못된 UUID·음수 식별자는 업무 조회 전 입력 오류로 차단한다. */
    @Test
    void 유효하지_않은_입력은_VALIDATION_FAILED다() {
        assertThatThrownBy(() -> service.create(command(" ")))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.create(new RedrawRequestCreateCommand(ADMIN_ID, EVENT_ID, REASON, "not-uuid")))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.create(new RedrawRequestCreateCommand(ADMIN_ID, 0L, REASON, IDEMPOTENCY_KEY)))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(memberQueryService, redrawRequestRepository, persistenceService);
    }

    /** 테스트용 요청 명령을 만든다. */
    private RedrawRequestCreateCommand command(String reason) {
        return new RedrawRequestCreateCommand(ADMIN_ID, EVENT_ID, reason, IDEMPOTENCY_KEY);
    }

    /** 테스트용 영속 RedrawRequest를 필요한 상태값으로 만든다. */
    private RedrawRequest request(Long id, String reason) {
        RedrawRequest request = org.mockito.Mockito.mock(RedrawRequest.class);
        lenient().when(request.getId()).thenReturn(id);
        lenient().when(request.getEventId()).thenReturn(EVENT_ID);
        lenient().when(request.getOriginalDrawingId()).thenReturn(20L);
        lenient().when(request.getVacancyCount()).thenReturn(2);
        lenient().when(request.getStatus()).thenReturn(RedrawRequestStatus.REQUESTED);
        lenient().when(request.getExecutionStatus()).thenReturn(RedrawExecutionStatus.PENDING);
        lenient().when(request.getRequestedBy()).thenReturn(ADMIN_ID);
        lenient().when(request.getReason()).thenReturn(reason);
        return request;
    }
}
