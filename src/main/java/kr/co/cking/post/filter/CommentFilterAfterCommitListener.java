package kr.co.cking.post.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 댓글 작성·수정이 Commit된 뒤에만 필터 판정을 요청한다.
 *
 * <p>Commit 전에 요청하면 필터 결과를 저장할 때 댓글 행이 아직 보이지 않을 수 있다. 필터가 꺼져 있거나 Executor 큐가 가득
 * 차 제출이 거절되면 댓글은 PENDING으로 남는다. 이미 Commit된 댓글 작성을 실패시키지 않도록 예외는 삼키고, 남은 댓글은
 * 재필터링이 다시 처리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class CommentFilterAfterCommitListener {

    private final ObjectProvider<CommentFilterDispatcher> dispatcherProvider;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(CommentFilterRequestedEvent event) {
        try {
            CommentFilterDispatcher dispatcher = dispatcherProvider.getIfAvailable();
            if (dispatcher == null) {
                log.debug("댓글 필터가 꺼져 있어 판정을 요청하지 않습니다. commentId={}", event.commentId());
                return;
            }
            dispatcher.dispatch(event.commentId());
        } catch (RuntimeException exception) {
            log.warn("댓글 필터 판정 요청을 제출하지 못했습니다. 재필터링이 다시 처리합니다. commentId={}",
                    event.commentId(), exception);
        }
    }
}
