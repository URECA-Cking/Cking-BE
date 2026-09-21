package kr.co.cking.stream.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamResolutionStatus;
import kr.co.cking.stream.repository.DeadStreamMessageRepository;

class DeadStreamAdminServiceTest {

    private static final Long ADMIN_ID = 1L;

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final DeadStreamMessageRepository repository = mock(DeadStreamMessageRepository.class);
    private final DeadStreamReplayService replayService = mock(DeadStreamReplayService.class);
    private final DeadStreamAdminService service =
            new DeadStreamAdminService(memberQueryService, repository, replayService);

    @Test
    void 목록은_ADMIN을_검증한_뒤_createdAt_id_오름차순으로_조회한다() {
        Page<DeadStreamMessage> page = new PageImpl<>(List.of());
        when(repository.findByResolutionStatus(any(), any(Pageable.class))).thenReturn(page);

        assertThat(service.list(ADMIN_ID, DeadStreamResolutionStatus.UNRESOLVED, 2, 30)).isSameAs(page);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        InOrder inOrder = inOrder(memberQueryService, repository);
        inOrder.verify(memberQueryService).validateAdmin(ADMIN_ID);
        inOrder.verify(repository).findByResolutionStatus(
                org.mockito.ArgumentMatchers.eq(DeadStreamResolutionStatus.UNRESOLVED), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(30);
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
    }

    @Test
    void ADMIN이_아니면_목록도_replay도_거부하고_아무것도_하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.list(ADMIN_ID, DeadStreamResolutionStatus.UNRESOLVED, 0, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.replay(ADMIN_ID, 5L)).isInstanceOf(BusinessException.class);

        verifyNoInteractions(repository, replayService);
    }

    @Test
    void replay는_ADMIN을_검증한_뒤_요청한_관리자를_처리자로_위임한다() {
        DeadStreamMessage message = mock(DeadStreamMessage.class);
        when(replayService.replay(5L, ADMIN_ID)).thenReturn(message);

        assertThat(service.replay(ADMIN_ID, 5L)).isSameAs(message);

        InOrder inOrder = inOrder(memberQueryService, replayService);
        inOrder.verify(memberQueryService).validateAdmin(ADMIN_ID);
        inOrder.verify(replayService).replay(5L, ADMIN_ID);
    }
}
