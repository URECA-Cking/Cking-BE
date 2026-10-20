package kr.co.cking.follow.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kr.co.cking.follow.application.dto.CreatorPostNewsView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 팔로우 관계와 공개 게시글을 한 번의 질의로 읽는다. 게시글 상태는 변경하지 않는다. */
@Repository
@RequiredArgsConstructor
public class CreatorPostNewsRepository {

    private final JdbcTemplate jdbc;

    /** 각 팔로우의 최신 공개 글을 기존 (creator_id, created_at, post_id) 인덱스로 찾는다. */
    public List<CreatorPostNewsView> findByMemberId(Long memberId) {
        return jdbc.query("""
                SELECT f.creator_id, COALESCE(f.last_read_at, f.created_at) AS read_cutoff,
                       (SELECT p.created_at
                        FROM creator_post p
                        WHERE p.creator_id = f.creator_id AND p.visibility IN ('PUBLIC', 'FOLLOWERS')
                        ORDER BY p.created_at DESC, p.post_id DESC LIMIT 1) AS latest_post_created_at
                FROM creator_follow f
                WHERE f.member_id = ?
                ORDER BY f.creator_id ASC
                """, (rs, rowNum) -> {
            Timestamp latest = rs.getTimestamp("latest_post_created_at");
            Instant cutoff = rs.getTimestamp("read_cutoff").toInstant();
            Instant latestAt = latest == null ? null : latest.toInstant();
            return new CreatorPostNewsView(rs.getLong("creator_id"),
                    latestAt != null && latestAt.isAfter(cutoff), latestAt);
        }, memberId);
    }
}
