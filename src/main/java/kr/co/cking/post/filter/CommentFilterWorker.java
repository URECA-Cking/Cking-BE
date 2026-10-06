package kr.co.cking.post.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;

/**
 * 댓글 하나를 필터 서비스에 판정시키고 결과를 저장한다. Executor 스레드에서 실행되며 어떤 실패도 밖으로 던지지 않는다.
 *
 * <p>필터 서비스 장애는 댓글을 통과 상태로 두고 FAILED로 기록해 재필터링이 다시 처리하게 한다. 이 클래스는 Transaction을
 * 열지 않는다. 읽기와 저장은 {@link CommentFilterResultService}가 각각 짧은 Transaction으로 처리한다.
 */
@Slf4j
@RequiredArgsConstructor
public class CommentFilterWorker {

    private final CommentFilterClient client;
    private final CommentFilterResultService resultService;

    public void process(Long commentId) {
        try {
            Optional<CommentFilterResultService.Target> target = resultService.findTarget(commentId);
            if (target.isEmpty()) {
                return;
            }
            Instant judgedUpdatedAt = target.get().updatedAt();

            CommentFilterResult result;
            try {
                result = client.moderate(commentId, target.get().content());
            } catch (CommentFilterRejectedException exception) {
                log.error("필터 서비스가 요청을 거절했습니다. 요청 형식을 확인하세요. commentId={}", commentId, exception);
                recordFailure(commentId, judgedUpdatedAt);
                return;
            } catch (RuntimeException exception) {
                log.warn("필터 서비스 호출에 실패했습니다. 재필터링이 다시 처리합니다. commentId={}", commentId, exception);
                recordFailure(commentId, judgedUpdatedAt);
                return;
            }

            boolean saved;
            try {
                saved = resultService.saveResult(commentId, judgedUpdatedAt, result);
            } catch (IllegalArgumentException exception) {
                log.error("판정 결과를 저장할 수 없습니다. commentId={}", commentId, exception);
                recordFailure(commentId, judgedUpdatedAt);
                return;
            }
            if (!saved) {
                log.debug("판정 중 댓글이 수정·삭제되어 결과를 버렸습니다. commentId={}", commentId);
            }
        } catch (RuntimeException exception) {
            log.error("댓글 필터 처리 중 예기치 못한 오류가 났습니다. commentId={}", commentId, exception);
        }
    }

    private void recordFailure(Long commentId, Instant judgedUpdatedAt) {
        try {
            resultService.saveFailure(commentId, judgedUpdatedAt);
        } catch (RuntimeException exception) {
            log.error("판정 실패를 기록하지 못했습니다. commentId={}", commentId, exception);
        }
    }
}
