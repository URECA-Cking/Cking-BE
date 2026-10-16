package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.application.CreatorPublicProfileQueryService;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.creator.application.dto.CreatorPublicProfile;
import kr.co.cking.member.application.ProfileImageUrlResolver;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.dto.CreatorPostCommentOriginalView;
import kr.co.cking.post.application.dto.CreatorPostCommentView;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.filter.CommentFilterRequestedEvent;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.sanction.application.SanctionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
 *
 * <p>작성·수정은 댓글 작성 제한 중인 회원을 {@code COMMENT_RESTRICTED}(403)로 막는다(이슈 #569). 삭제는 막지 않는다.
 * 제한 확인은 <b>작성자 행을 쓰기 잠금으로 읽은 뒤</b> 한다. 일반 조회로 시작하면 MySQL 기본 REPEATABLE READ가 그 시점의
 * 스냅샷을 고정해, 그 사이 관리자가 댓글을 숨기거나 수동 제한을 확정해도 이 요청은 제한을 못 보고 댓글을 저장할 수 있다.
 * 잠금 읽기는 최신 커밋을 읽고 스냅샷은 잠금 이후 첫 일반 조회에서 만들어지므로, 제재 처리(작성자 행 잠금)가 끝날 때까지
 * 기다린 뒤 그 결과를 본다. 잠금 순서는 작성자 → 게시글 → 댓글로 숨김 처리·신고 접수와 같다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CreatorPostCommentService {

    private final PostAccessPolicy accessPolicy;
    private final CreatorPostCommentRepository commentRepository;
    private final MemberRepository memberRepository;
    private final CreatorPublicProfileQueryService creatorPublicProfileQueryService;
    private final CreatorQueryService creatorQueryService;
    private final ProfileImageUrlResolver profileImageUrlResolver;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final SanctionQueryService sanctionQueryService;

    /** @param viewerMemberId 비로그인이면 null */
    @Transactional(readOnly = true)
    public Page<CreatorPostCommentView> findByPost(
            Long creatorId, Long postId, Long viewerMemberId, Pageable pageable) {
        Creator creator = accessPolicy.requireCreator(creatorId);
        accessPolicy.requireViewablePost(creator, postId, viewerMemberId);

        Page<CreatorPostComment> comments = commentRepository.findByPostIdOldestFirst(postId, pageable);
        Map<Long, Member> authors = comments.isEmpty()
                ? Map.of()
                : memberRepository.findAllById(comments.map(CreatorPostComment::getMemberId).toSet()).stream()
                        .collect(Collectors.toMap(Member::getMemberId, Function.identity()));
        Map<Long, CreatorPublicProfile> creatorProfiles = comments.isEmpty()
                ? Map.of()
                : creatorPublicProfileQueryService.findByMemberIds(authors.keySet());
        return comments.map(comment ->
                toView(comment, creator, authors.get(comment.getMemberId()),
                        creatorProfiles.get(comment.getMemberId()), viewerMemberId));
    }

    /**
     * 필터링(BLOCK)된 댓글의 원문. 게시글을 볼 수 있는 사람이면 누구나 요청할 수 있다. 개인정보 규칙으로 막힌 댓글과
     * 관리자가 숨긴 댓글은 {@code COMMENT_NOT_REVEALABLE}(403)이다. 필터링되지 않았거나 조회자가 작성자 본인이면 필터링된 댓글로 보이지
     * 않으므로 404다.
     *
     * @param viewerMemberId 비로그인이면 null
     */
    @Transactional(readOnly = true)
    public CreatorPostCommentOriginalView findOriginal(
            Long creatorId, Long postId, Long commentId, Long viewerMemberId) {
        Creator creator = accessPolicy.requireCreator(creatorId);
        accessPolicy.requireViewablePost(creator, postId, viewerMemberId);

        CreatorPostComment comment = commentRepository.findById(commentId)
                .filter(found -> found.belongsTo(postId))
                .filter(found -> found.isConcealed() && !found.isWrittenBy(viewerMemberId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (comment.isBlockedForPrivacy() || comment.isHiddenByAdmin()) {
            throw new BusinessException(PostErrorCode.COMMENT_NOT_REVEALABLE);
        }
        return new CreatorPostCommentOriginalView(comment.getCommentId(), comment.getContent());
    }

    public CreatorPostCommentView create(Long memberId, Long creatorId, Long postId, String content) {
        Member author = requireMember(memberId);
        requireCommentProfileConfigured(author, memberId);
        sanctionQueryService.requireCommentWriteAllowed(memberId, creatorId);
        Creator creator = accessPolicy.requireCreator(creatorId);
        accessPolicy.requireViewablePostForCommentWrite(creator, postId, memberId);
        requireCanComment(creator, memberId);

        CreatorPostComment comment = commentRepository.save(
                new CreatorPostComment(postId, memberId, validate(content), clock.instant()));
        eventPublisher.publishEvent(new CommentFilterRequestedEvent(comment.getCommentId()));
        return toView(comment, creator, author, creatorPublicProfile(memberId), memberId);
    }

    public CreatorPostCommentView update(
            Long memberId, Long creatorId, Long postId, Long commentId, String content) {
        Member author = requireMember(memberId);
        requireCommentProfileConfigured(author, memberId);
        sanctionQueryService.requireCommentWriteAllowed(memberId, creatorId);
        Creator creator = accessPolicy.requireCreator(creatorId);
        accessPolicy.requireViewablePostForCommentWrite(creator, postId, memberId);
        CreatorPostComment comment = requireCommentForUpdate(postId, commentId);
        if (!comment.isWrittenBy(memberId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        requireCanComment(creator, memberId);

        comment.update(validate(content), clock.instant());
        eventPublisher.publishEvent(new CommentFilterRequestedEvent(comment.getCommentId()));
        return toView(comment, creator, author, creatorPublicProfile(memberId), memberId);
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

    /**
     * 작성·수정하는 회원을 쓰기 잠금으로 읽는다. 이 호출이 트랜잭션의 첫 DB 접근이어야 제재 확인이 잠금 이후의 상태를 본다.
     * 같은 회원의 제재 처리(숨김·수동 제한)와 직렬화된다.
     */
    private Member requireMember(Long memberId) {
        return memberRepository.findByIdForUpdate(memberId)
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

    /**
     * 필터링(BLOCK)됐거나 관리자가 숨긴 댓글은 작성자 본인이 아닌 조회자에게 원문과 작성자 이름·프로필 사진을 빼고
     * 내려준다. 작성자 본인에게는 가려졌는지 알리지 않고 모두 그대로 보여준다. 판정 사유는 응답에 담지 않는다. 관리자가
     * 숨긴 댓글은 원문 보기도 허용하지 않는다(revealable=false).
     *
     * <p>Creator 계정의 댓글은 이름이 활동명이고 사진은 Creator Space 프로필 사진이다. 그 밖의 댓글은 회원의 공개 이름과
     * 프로필 사진이며, 사진을 설정하지 않았거나 회원을 찾지 못하면 null이다.
     *
     * @param author 작성 회원. 찾지 못하면 null
     * @param creatorProfile 작성자가 Creator면 그 공개 프로필, 아니면 null
     * @param viewerMemberId 비로그인이면 null
     */
    private CreatorPostCommentView toView(
            CreatorPostComment comment, Creator creator, Member author, CreatorPublicProfile creatorProfile,
            Long viewerMemberId) {
        boolean filtered = comment.isConcealed() && !comment.isWrittenBy(viewerMemberId);
        boolean writtenByCreator = isWrittenByOwner(creator, comment.getMemberId());
        String authorName = creatorProfile != null
                ? creatorProfile.name()
                : (author == null ? null : author.publicDisplayName());
        String authorPhotoUrl = creatorProfile != null
                ? creatorProfile.profileImageUrl()
                : (author == null ? null : profileImageUrlResolver.resolve(author.getProfileImageKey()));
        return new CreatorPostCommentView(
                comment.getCommentId(),
                comment.getPostId(),
                comment.getMemberId(),
                filtered ? null : authorName,
                filtered ? null : authorPhotoUrl,
                writtenByCreator,
                filtered ? null : comment.getContent(),
                filtered,
                filtered && !comment.isBlockedForPrivacy() && !comment.isHiddenByAdmin(),
                comment.getCreatedAt(),
                comment.getUpdatedAt());
    }

    private boolean isWrittenByOwner(Creator creator, Long commentMemberId) {
        return creator.getMemberId().equals(commentMemberId);
    }

    private void requireCommentProfileConfigured(Member author, Long memberId) {
        if (creatorQueryService.requireActivityNameConfiguredForCreator(memberId)) {
            return;
        }
        author.requireProfileConfigured();
    }

    private CreatorPublicProfile creatorPublicProfile(Long memberId) {
        return creatorPublicProfileQueryService.findByMemberIds(Set.of(memberId)).get(memberId);
    }
}
