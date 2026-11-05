package kr.co.cking.post.application;

import kr.co.cking.creator.application.CreatorPublicProfileQueryService;
import kr.co.cking.creator.application.dto.CreatorPublicProfile;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.post.application.dto.AdminFilteredCommentView;
import kr.co.cking.post.domain.CommentFilterAction;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자가 필터가 차단한 댓글을 신고 여부와 상관없이 최근 판정 순으로 조회한다(이슈 #809). 이미 차단된 댓글은 새 신고를
 * 받지 않아 신고 목록만으로는 찾을 수 없다.
 */
@Service
@RequiredArgsConstructor
public class AdminFilteredCommentQueryService {

    static final int POST_PREVIEW_LENGTH = 50;

    private final MemberQueryService memberQueryService;
    private final CreatorPublicProfileQueryService creatorPublicProfileQueryService;
    private final CreatorPostCommentRepository commentRepository;
    private final CreatorPostRepository postRepository;

    @Transactional(readOnly = true)
    public Page<AdminFilteredCommentView> findFilteredComments(Long adminId, Pageable pageable) {
        memberQueryService.validateAdmin(adminId);

        Page<CreatorPostComment> comments =
                commentRepository.findByFilterActionLatestFirst(CommentFilterAction.BLOCK, pageable);
        Map<Long, CreatorPost> posts = postRepository.findAllById(comments.map(CreatorPostComment::getPostId).toSet())
                .stream()
                .collect(Collectors.toMap(CreatorPost::getPostId, Function.identity()));
        Map<Long, CreatorPublicProfile> spaceCreators = creatorPublicProfileQueryService.findByCreatorIds(
                posts.values().stream().map(CreatorPost::getCreatorId).collect(Collectors.toSet()));
        Set<Long> authorIds = comments.map(CreatorPostComment::getMemberId).toSet();
        Map<Long, CreatorPublicProfile> creatorAuthors = creatorPublicProfileQueryService.findByMemberIds(authorIds);
        Map<Long, MemberInfo> members = memberQueryService.findMemberInfosByIds(authorIds);

        return comments.map(comment -> {
            CreatorPost post = posts.get(comment.getPostId());
            CreatorPublicProfile spaceCreator = spaceCreators.get(post.getCreatorId());
            return new AdminFilteredCommentView(
                    comment.getCommentId(),
                    comment.getPostId(),
                    preview(post.getContent()),
                    post.getCreatorId(),
                    spaceCreator == null ? null : spaceCreator.name(),
                    comment.getMemberId(),
                    authorName(creatorAuthors.get(comment.getMemberId()), members.get(comment.getMemberId())),
                    comment.getContent(),
                    comment.blockReasons(),
                    comment.getFilterStatus(),
                    comment.getFilteredAt());
        });
    }

    /** 댓글 목록과 같은 규칙이다. Creator 계정은 활동명, 그 밖의 회원은 닉네임이고 닉네임이 없으면 미설정 문구다. */
    private String authorName(CreatorPublicProfile creatorAuthor, MemberInfo member) {
        if (creatorAuthor != null) {
            return creatorAuthor.name();
        }
        return member == null ? null : member.name();
    }

    private String preview(String content) {
        if (content.codePointCount(0, content.length()) <= POST_PREVIEW_LENGTH) {
            return content;
        }
        return content.substring(0, content.offsetByCodePoints(0, POST_PREVIEW_LENGTH));
    }
}
