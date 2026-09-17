package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.application.VerifiedSnapshotTestFactory;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InitialDrawingPreparationServiceTest {

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private EventDrawingQueryService eventDrawingQueryService;

    @Mock
    private SnapshotIntegrityService snapshotIntegrityService;

    @InjectMocks
    private InitialDrawingPreparationService service;

    @Test
    void CLOSED_Event과_검증된_Snapshot이면_실행_준비_정보를_반환한다() {
        VerifiedSnapshot snapshot = VerifiedSnapshotTestFactory.create(
                20L, 10L, 2, "WEIGHTED", "WEIGHTED_V1");
        when(eventDrawingQueryService.getDrawingSource(10L))
                .thenReturn(source(EventStatus.CLOSED, null));
        when(snapshotIntegrityService.verifyForDrawing(10L)).thenReturn(snapshot);

        InitialDrawingPreparation result = service.prepare(1L, 10L);

        assertThat(result.verifiedSnapshot()).isSameAs(snapshot);
        assertThat(result.eventId()).isEqualTo(10L);
        assertThat(result.snapshotId()).isEqualTo(20L);
        assertThat(result.winnerCount()).isEqualTo(2);
        assertThat(result.drawMethod()).isEqualTo("WEIGHTED");
        assertThat(result.algorithmVersion()).isEqualTo("WEIGHTED_V1");
        assertThat(result.candidateCount()).isZero();
        verify(memberQueryService).validateAdmin(1L);
        verify(snapshotIntegrityService).verifyForDrawing(10L);
    }

    @Test
    void 존재하지_않는_관리자는_Event을_조회하지_않는다() {
        org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .when(memberQueryService).validateAdmin(999L);

        assertThatThrownBy(() -> service.prepare(999L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        verify(eventDrawingQueryService, never()).getDrawingSource(10L);
    }

    @Test
    void ADMIN이_아니면_실행_준비를_진행하지_않는다() {
        org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(memberQueryService).validateAdmin(2L);

        assertThatThrownBy(() -> service.prepare(2L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        verify(eventDrawingQueryService, never()).getDrawingSource(10L);
    }

    @Test
    void CLOSED가_아닌_Event은_INVALID_STATE를_반환한다() {
        when(eventDrawingQueryService.getDrawingSource(10L))
                .thenReturn(source(EventStatus.OPEN, null));

        assertThatThrownBy(() -> service.prepare(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_STATE);

        verify(snapshotIntegrityService, never()).verifyForDrawing(10L);
    }

    @Test
    void 삭제된_Event은_INVALID_STATE를_반환한다() {
        when(eventDrawingQueryService.getDrawingSource(10L))
                .thenReturn(source(EventStatus.CLOSED, Instant.parse("2026-09-17T00:00:00Z")));

        assertThatThrownBy(() -> service.prepare(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_STATE);

        verify(snapshotIntegrityService, never()).verifyForDrawing(10L);
    }

    @Test
    void 공식_Snapshot이_없으면_Snapshot_오류를_그대로_반환한다() {
        when(eventDrawingQueryService.getDrawingSource(10L))
                .thenReturn(source(EventStatus.CLOSED, null));
        when(snapshotIntegrityService.verifyForDrawing(10L))
                .thenThrow(new BusinessException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));

        assertThatThrownBy(() -> service.prepare(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(SnapshotErrorCode.SNAPSHOT_NOT_FOUND);
    }

    @Test
    void Snapshot_Hash가_일치하지_않으면_실행을_중단한다() {
        when(eventDrawingQueryService.getDrawingSource(10L))
                .thenReturn(source(EventStatus.CLOSED, null));
        when(snapshotIntegrityService.verifyForDrawing(10L))
                .thenThrow(new BusinessException(SnapshotErrorCode.SNAPSHOT_HASH_MISMATCH));

        assertThatThrownBy(() -> service.prepare(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(SnapshotErrorCode.SNAPSHOT_HASH_MISMATCH);
    }

    private EventDrawingSource source(EventStatus status, Instant deletedAt) {
        return new EventDrawingSource(10L, status, deletedAt);
    }
}
