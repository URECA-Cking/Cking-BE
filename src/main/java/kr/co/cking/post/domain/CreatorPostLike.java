package kr.co.cking.post.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 회원이 게시글에 누른 좋아요(이슈 #554).
 *
 * <p>생성은 중복 쌍을 무시하는 native insert로만 한다({@code CreatorPostLikeRepository#insertIfAbsent}).
 */
@Entity
@Table(name = "creator_post_like")
@IdClass(CreatorPostLike.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorPostLike {

    @Id
    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Id
    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long postId;
        private Long memberId;
    }
}
