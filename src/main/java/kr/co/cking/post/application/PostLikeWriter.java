package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.repository.CreatorPostLikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 게시글 좋아요·취소의 DB 변경(이슈 #554). 한 Transaction 안에서 끝나며, Commit 뒤의 후속 처리(미션 보상)는
 * {@link PostLikeService}가 Transaction 밖에서 한다.
 *
 * <p>둘 다 멱등하다. 이미 좋아요한 게시글에 다시 좋아요하거나, 좋아요하지 않은 게시글을 취소해도 성공한다. 중복은
 * (post_id, member_id) PK가 막고, 동시 요청은 중복 키 충돌을 무시하는 insert로 하나만 남긴다. 게시글 행은
 * 공유 잠금으로 읽어(댓글 작성과 같은 방식) 동시에 진행 중인 게시글 삭제가 끝난 뒤 404로 응답한다.
 */
@Component
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
class PostLikeWriter {

    private final PostAccessPolicy accessPolicy;
    private final MemberRepository memberRepository;
    private final CreatorPostLikeRepository likeRepository;
    private final Clock clock;

    /**
     * 게시글을 볼 수 있는 회원만 좋아요할 수 있다. 팔로워 공개 게시글은 팔로워와 작성 Creator 본인만 가능하다
     * ({@code POST_FOLLOWERS_ONLY}).
     */
    Result like(Long memberId, Long creatorId, Long postId) {
        requireMember(memberId);
        Creator creator = accessPolicy.requireCreator(creatorId);
        CreatorPost post = accessPolicy.requireViewablePostForCommentWrite(creator, postId, memberId);
        Instant now = clock.instant();
        likeRepository.insertIfAbsent(post.getPostId(), memberId, now);
        // insert의 영향 행 수로 새 좋아요를 판단하지 않는다(드라이버 설정에 따라 기존 행에도 1이 온다). DB에 있는 생성
        // 시각을 읽어 오늘 눌린 좋아요인지 판단한다. 새로 넣었으면 방금 넣은 시각, 이미 있었으면 그때의 시각이다.
        Instant likedAt = likeRepository.findCreatedAt(post.getPostId(), memberId).orElse(null);
        return new Result(creator, isSameUtcDay(likedAt, now), now, likeRepository.countByPostId(post.getPostId()));
    }

    /** 공개 범위와 팔로우를 보지 않는다. 팔로우를 끊은 뒤에도 자기 좋아요는 취소할 수 있다. */
    Result unlike(Long memberId, Long creatorId, Long postId) {
        requireMember(memberId);
        Creator creator = accessPolicy.requireCreator(creatorId);
        CreatorPost post = accessPolicy.requirePostOfForCommentWrite(creator, postId);
        likeRepository.deleteByPostIdAndMemberId(post.getPostId(), memberId);
        return new Result(creator, false, clock.instant(), likeRepository.countByPostId(post.getPostId()));
    }

    private void requireMember(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    /**
     * 좋아요를 누른 날(UTC)과 요청한 날이 같은지. 미션 보상은 이 날짜 기준으로 하루 1회이므로, 날짜가 바뀐 뒤 같은
     * POST만 다시 보내는 요청(좋아요를 유지한 채 보내는 중복 요청)을 새 좋아요로 취급하지 않기 위해 쓴다.
     */
    private static boolean isSameUtcDay(Instant likedAt, Instant now) {
        return likedAt != null
                && likedAt.atZone(ZoneOffset.UTC).toLocalDate().equals(now.atZone(ZoneOffset.UTC).toLocalDate());
    }

    /**
     * @param likedToday (좋아요) 현재 좋아요가 오늘(UTC) 눌린 것인지. 새로 추가했거나 오늘 눌러 둔 좋아요면 true,
     *                   이전 날에 눌러 둔 좋아요면 false. 취소 결과에서는 항상 false
     * @param decidedAt  {@code likedToday}를 판정한 시각. 미션 보상은 이 시각의 날짜로 지급해, 판정과 지급 사이에
     *                   UTC 자정을 넘겨도 두 날짜가 어긋나지 않게 한다
     */
    record Result(Creator creator, boolean likedToday, Instant decidedAt, long likeCount) {
    }
}
