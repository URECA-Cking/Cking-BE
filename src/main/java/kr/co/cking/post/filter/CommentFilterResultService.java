package kr.co.cking.post.filter;

import kr.co.cking.post.domain.CommentFilterStatus;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 필터 판정의 읽기·저장을 짧은 Transaction으로 나눈다. 필터 서비스 호출은 이 Service 밖에서 하므로 HTTP 응답을 기다리는 동안
 * DB 연결을 잡지 않는다.
 *
 * <p>저장은 댓글 행을 쓰기 잠금으로 읽은 뒤 판정을 요청했을 때의 본문과 현재 본문이 같을 때만 반영한다. 시각이 아니라
 * 본문을 직접 비교하므로 시각 정밀도에 기대지 않는다. 판정 중에 본문이 수정되면 이전 본문에 대한 결과는 버린다(수정은
 * 새 요청을 이미 발행했다). 수정했다가 원래 본문으로 돌아온 경우는 결과가 지금 본문에도 유효하므로 반영한다.
 * 댓글이 삭제된 경우도 버린다. 잠그는 행은 댓글 하나뿐이라 게시글 → 댓글 순서의 다른 경로와 교착이 생기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CommentFilterResultService {

    private final CreatorPostCommentRepository commentRepository;
    private final Clock clock;

    /** 판정을 요청한 본문. 저장 시 현재 본문과 비교해 그사이 수정됐는지 확인한다. */
    public record Target(String content) {
    }

    /** 판정이 필요한 댓글을 읽는다. 이미 판정을 마쳤거나 삭제된 댓글이면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<Target> findTarget(Long commentId) {
        return commentRepository.findById(commentId)
                .filter(comment -> comment.getFilterStatus() != CommentFilterStatus.DONE)
                .map(comment -> new Target(comment.getContent()));
    }

    /** @return 판정을 반영했으면 true, 그사이 수정·삭제·판정 완료로 버렸으면 false */
    @Transactional
    public boolean saveResult(Long commentId, String judgedContent, CommentFilterResult result) {
        return applyIfCurrent(commentId, judgedContent, comment -> comment.markFiltered(
                result.action(), result.reasons(), result.ruleVersion(), result.modelVersion(), clock.instant()));
    }

    /** @return 실패를 반영했으면 true, 그사이 수정·삭제·판정 완료로 버렸으면 false */
    @Transactional
    public boolean saveFailure(Long commentId, String judgedContent) {
        return applyIfCurrent(commentId, judgedContent, CreatorPostComment::markFilterFailed);
    }

    private boolean applyIfCurrent(Long commentId, String judgedContent, Consumer<CreatorPostComment> change) {
        Optional<CreatorPostComment> found = commentRepository.findByIdForUpdate(commentId);
        if (found.isEmpty()) {
            return false;
        }
        CreatorPostComment comment = found.get();
        if (comment.getFilterStatus() == CommentFilterStatus.DONE
                || !comment.getContent().equals(judgedContent)) {
            return false;
        }
        change.accept(comment);
        return true;
    }
}
