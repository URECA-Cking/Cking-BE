package kr.co.cking.post;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.CreatorPostImage;
import kr.co.cking.post.domain.PostImageStatus;
import kr.co.cking.post.domain.PostVisibility;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

/** 게시글 단위 테스트용 도메인 객체. ID는 저장 없이 직접 채운다. */
public final class PostFixtures {

    public static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    private PostFixtures() {
    }

    public static Member member(Long memberId) {
        Member member = new Member("member-" + memberId, null, null, MemberRole.USER);
        ReflectionTestUtils.setField(member, "memberId", memberId);
        return member;
    }

    public static Creator creator(Long creatorId, Long ownerMemberId) {
        Creator creator = new Creator(ownerMemberId, "creator-" + creatorId);
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }

    public static CreatorPost post(Long postId, Long creatorId, PostVisibility visibility) {
        CreatorPost post = new CreatorPost(creatorId, "본문 " + postId, visibility, NOW);
        ReflectionTestUtils.setField(post, "postId", postId);
        return post;
    }

    public static CreatorPostImage linkedImage(String objectKey, Long creatorId, Long postId, int displayOrder) {
        CreatorPostImage image = CreatorPostImage.uploading(objectKey, creatorId, NOW);
        ReflectionTestUtils.setField(image, "status", PostImageStatus.UPLOADED);
        ReflectionTestUtils.setField(image, "postId", postId);
        ReflectionTestUtils.setField(image, "displayOrder", displayOrder);
        return image;
    }
}
