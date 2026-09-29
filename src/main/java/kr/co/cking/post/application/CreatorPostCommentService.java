package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.dto.CreatorPostCommentView;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Creator Space 게시글 댓글(이슈 #341).
 *
 * <ul>
 *   <li>조회: 게시글을 볼 수 있는 사람. 팔로워 공개 게시글은 팔로워와 작성한 Creator 본인만 볼 수 있다.</li>
 *   <li>작성·수정: 로그인 + 팔로워 또는 작성한 Creator 본인. 전체 공개 게시글도 팔로우해야 쓸 수 있다.</li>
 *   <li>삭제: 댓글 작성자 본인과 게시글을 작성한 Creator 본인. 팔로우를 끊은 작성자도 자기 댓글은 지울 수 있다.</li>
 * </ul>
 *
 * <p>작성·수정·삭제는 게시글 행을 공유 잠금으로 읽어 동시에 진행 중인 게시글 삭제와 직렬화한다. 삭제가 먼저 끝나면
 * FK 오류 대신 404가 된다. 수정·삭제는 이어서 댓글 행을 쓰기 잠금으로 읽어 같은 댓글의 동시 수정·삭제도 직렬화한다.
 * 잠금 순서는 항상 게시글 → 댓글이며, 게시글 삭제도 같은 순서라 교착이 생기지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CreatorPostCommentService {

    private final PostAccessPolicy accessPolicy;
    private final CreatorPostCommentRepository commentRepository;
    private final MemberRepository memberRepository;
    private final Clock clock;

    /** @param viewerMemberId 비로그인이면 null */
    @Transactional(readOnly = true)
    public Page<CreatorPostCommentView> findByPost(
            Long creatorId, Long postId, Long viewerMemberId, Pageable pageable) {
        Creator creator = accessPolicy.requireCreator(creatorId);
        accessPolicy.requireViewablePost(creator, postId, viewerMemberId);

        Page<CreatorPostComment> comments = commentRepository.findByPostIdOldestFirst(postId, pageable);
        Map<Long, String> authorNames = comments.isEmpty()
                ? Map.of()
                : memberRepository.findAllById(comments.map(CreatorPostComment::getMemberId).toSet()).stream()
                        .collect(Collectors.toMap(Member::getMemberId, Member::getName));
        return comments.map(comment -> toView(comment, creator, authorNames.get(comment.getMemberId())));
    }

    public CreatorPostCommentView create(Long memberId, Long creatorId, Long postId, String content) {
        Member author = requireMember(memberId);
        Creator creator = accessPolicy.requireCreator(creatorId);
        accessPolicy.requireViewablePostForCommentWrite(creator, postId, memberId);
        requireCanComment(creator, memberId);

        CreatorPostComment comment = commentRepository.save(
                new CreatorPostComment(postId, memberId, validate(content), clock.instant()));
        return toView(comment, creator, author.getName());
    }

    public CreatorPostCommentView update(
            Long memberId, Long creatorId, Long postId, Long commentId, String content) {
        Member author = requireMember(memberId);
        Creator creator = accessPolicy.requireCreator(creatorId);
        accessPolicy.requireViewablePostForCommentWrite(creator, postId, memberId);
        CreatorPostComment comment = requireCommentForUpdate(postId, commentId);
        if (!comment.isWrittenBy(memberId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        requireCanComment(creator, memberId);

        comment.update(validate(content), clock.instant());
        return toView(comment, creator, author.getName());
    }

    /** 팔로우와 공개 범위를 보지 않는다. 팔로우를 끊은 작성자도 자기 댓글은 지울 수 있어야 하기 때문이다. */
    public void delete(Long memberId, Long creatorId, Long postId, Long commentId) {
        Creator creator = accessPolicy.requireCreator(creatorId);
        accessPolicy.requirePostOfForCommentWrite(creator, postId);
        CreatorPostComment comment = requireCommentForUpdate(postId, commentId);
        if (!comment.isWrittenBy(memberId) && !creator.getMemberId().equals(memberId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        commentRepository.delete(comment);
    }

    private Member requireMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireCanComment(Creator creator, Long memberId) {
        if (!accessPolicy.isFollowerOrOwner(creator, memberId)) {
            throw new BusinessException(PostErrorCode.COMMENT_FOLLOWERS_ONLY);
        }
    }

    /**
     * 수정·삭제할 댓글을 쓰기 잠금으로 읽어 같은 댓글에 대한 동시 수정·삭제를 직렬화한다. 다른 게시글의 댓글 ID는 없는
     * 댓글로 본다.
     */
    private CreatorPostComment requireCommentForUpdate(Long postId, Long commentId) {
        return commentRepository.findByIdForUpdate(commentId)
                .filter(comment -> comment.belongsTo(postId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private String validate(String content) {
        if (content == null || content.isBlank() || content.length() > CreatorPostComment.MAX_CONTENT_LENGTH) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return content;
    }

    private CreatorPostCommentView toView(CreatorPostComment comment, Creator creator, String authorName) {
        return new CreatorPostCommentView(
                comment.getCommentId(),
                comment.getPostId(),
                comment.getMemberId(),
                authorName,
                creator.getMemberId().equals(comment.getMemberId()),
                comment.getContent(),
                comment.getCreatedAt(),
                comment.getUpdatedAt());
    }
}
