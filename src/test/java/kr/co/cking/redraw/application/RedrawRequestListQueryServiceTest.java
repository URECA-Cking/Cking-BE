package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestStatus;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** RedrawRequest 관리자 목록 조회의 권한·필터·Drawing 일괄 보강을 검증한다. */
@ExtendWith(MockitoExtension.class)
class RedrawRequestListQueryServiceTest {

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private RedrawRequestRepository redrawRequestRepository;

    @Mock
    private DrawingRepository drawingRepository;

    @InjectMocks
    private RedrawRequestListQueryService service;

    /** 관리자는 두 상태 필터에 맞는 요청과 연결된 REDRAW Drawing ID를 함께 조회한다. */
    @Test
    void 관리자는_요청과_실행_상태를_조합해_목록을_조회한다() {
        PageRequest pageable = PageRequest.of(0, 20);
        RedrawRequest request = RedrawRequest.requested(10L, 20L, 1, "사유", "key", 1L);
        setId(request, 30L);
        setRequestedAt(request, Instant.parse("2026-10-07T00:00:00Z"));
        request.approve(2L);
        Drawing drawing = org.mockito.Mockito.mock(Drawing.class);
        when(drawing.getRedrawRequestId()).thenReturn(30L);
        when(drawing.getId()).thenReturn(40L);
        when(redrawRequestRepository.findForAdminList(
                RedrawRequestStatus.APPROVED, RedrawExecutionStatus.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(request), pageable, 1));
        when(drawingRepository.findByRedrawRequestIdIn(List.of(30L))).thenReturn(List.of(drawing));

        var result = service.list(2L, RedrawRequestStatus.APPROVED, RedrawExecutionStatus.PENDING, pageable);

        assertThat(result.getContent()).singleElement().satisfies(item -> {
            assertThat(item.redrawRequestId()).isEqualTo(30L);
            assertThat(item.redrawDrawingId()).isEqualTo(40L);
            assertThat(item.status()).isEqualTo(RedrawRequestStatus.APPROVED);
            assertThat(item.executionStatus()).isEqualTo(RedrawExecutionStatus.PENDING);
        });
        verify(memberQueryService).validateAdmin(2L);
        verify(redrawRequestRepository).findForAdminList(
                RedrawRequestStatus.APPROVED, RedrawExecutionStatus.PENDING, pageable);
    }

    /** 리플렉션으로 영속화 뒤에만 채워지는 테스트용 식별자·생성 시각을 설정한다. */
    private void setId(RedrawRequest request, Long id) {
        setField(request, "id", id);
    }

    /** 리플렉션으로 영속화 뒤에만 채워지는 테스트용 요청 시각을 설정한다. */
    private void setRequestedAt(RedrawRequest request, Instant requestedAt) {
        setField(request, "requestedAt", requestedAt);
    }

    /** 테스트 픽스처에 필요한 비공개 영속 필드를 설정한다. */
    private void setField(RedrawRequest request, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = RedrawRequest.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(request, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
