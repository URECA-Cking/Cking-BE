package kr.co.cking.follow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 사용자가 크리에이터를 팔로우한 관계.
 *
 * <p>생성은 중복 쌍을 무시하는 native insert로만 한다({@code CreatorFollowRepository#insertIfAbsent}).
 */
@Entity
@Table(name = "creator_follow")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorFollow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "follow_id")
    private Long followId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
