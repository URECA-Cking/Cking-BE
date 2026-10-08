package kr.co.cking.post.filter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CommentFilterAfterCommitListenerTest {

    private static final CommentFilterRequestedEvent EVENT = new CommentFilterRequestedEvent(500L);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<CommentFilterDispatcher> dispatcherProvider = mock(ObjectProvider.class);
    private final CommentFilterDispatcher dispatcher = mock(CommentFilterDispatcher.class);
    private final CommentFilterAfterCommitListener listener = new CommentFilterAfterCommitListener(dispatcherProvider);

    @Test
    void 필터가_켜져_있으면_커밋된_댓글을_Dispatcher에_제출한다() {
        given(dispatcherProvider.getIfAvailable()).willReturn(dispatcher);

        listener.onRequested(EVENT);

        verify(dispatcher).dispatch(500L);
    }

    @Test
    void 필터가_꺼져_있어_Dispatcher가_없으면_아무것도_하지_않고_예외도_내지_않는다() {
        given(dispatcherProvider.getIfAvailable()).willReturn(null);

        assertThatCode(() -> listener.onRequested(EVENT)).doesNotThrowAnyException();
    }

    @Test
    void 큐가_가득_차_제출이_거절돼도_이미_커밋된_댓글_작성에_영향을_주지_않는다() {
        given(dispatcherProvider.getIfAvailable()).willReturn(dispatcher);
        willThrow(new TaskRejectedException("full")).given(dispatcher).dispatch(500L);

        assertThatCode(() -> listener.onRequested(EVENT)).doesNotThrowAnyException();
    }

    @Test
    void Dispatcher를_가져오다_예외가_나도_삼킨다() {
        given(dispatcherProvider.getIfAvailable()).willThrow(new IllegalStateException("boom"));

        assertThatCode(() -> listener.onRequested(EVENT)).doesNotThrowAnyException();
    }
}
