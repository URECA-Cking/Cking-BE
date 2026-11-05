package kr.co.cking.post.application;

import kr.co.cking.creator.application.CreatorPublicProfileQueryService;
import kr.co.cking.creator.application.dto.CreatorPublicProfile;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.post.application.dto.AdminCommentDisplay;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.repository.CreatorPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 댓글 목록의 표시 값을 한 페이지 단위로 모아 읽는다. 필터 차단 목록(이슈 #809)과 신고 목록(이슈 #811)이 같은 규칙을
 * 쓰게 한다. 댓글 수만큼 쿼리를 반복하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminCommentDisplayReader {

    static final int POST_PREVIEW_LENGTH = 50;

    private final MemberQueryService memberQueryService;
    private final CreatorPublicProfileQueryService creatorPublicProfileQueryService;
    private final CreatorPostRepository postRepository;

    /** 댓글 ID별 표시 값을 돌려준다. */
    public Map<Long, AdminCommentDisplay> readByCommentId(Collection<CreatorPostComment> comments) {
        Map<Long, CreatorPost> posts = postRepository.findAllById(
                        comments.stream().map(CreatorPostComment::getPostId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(CreatorPost::getPostId, Function.identity()));
        Map<Long, CreatorPublicProfile> spaceCreators = creatorPublicProfileQueryService.findByCreatorIds(
                posts.values().stream().map(CreatorPost::getCreatorId).collect(Collectors.toSet()));
        Set<Long> authorIds = comments.stream().map(CreatorPostComment::getMemberId).collect(Collectors.toSet());
        Map<Long, CreatorPublicProfile> creatorAuthors = creatorPublicProfileQueryService.findByMemberIds(authorIds);
        Map<Long, MemberInfo> members = memberQueryService.findMemberInfosByIds(authorIds);

        return comments.stream().collect(Collectors.toMap(CreatorPostComment::getCommentId, comment -> {
            CreatorPost post = posts.get(comment.getPostId());
            String authorName =
                    authorName(creatorAuthors.get(comment.getMemberId()), members.get(comment.getMemberId()));
            if (post == null) {
                return new AdminCommentDisplay(null, null, null, authorName);
            }
            CreatorPublicProfile spaceCreator = spaceCreators.get(post.getCreatorId());
            return new AdminCommentDisplay(
                    post.getCreatorId(),
                    spaceCreator == null ? null : spaceCreator.name(),
                    preview(post.getContent()),
                    authorName);
        }));
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
