package kr.co.cking.drawing.repository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** REDRAW 구현 여부와 무관하게 당시 Drawing에 고정된 제외 명단만 읽는 조회 경계다. */
@Repository
@RequiredArgsConstructor
public class DrawingExclusionQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<Long> findMemberIdsByDrawingId(Long drawingId) {
        return jdbcTemplate.queryForList(
                """
                SELECT member_id
                FROM redraw_exclusion
                WHERE drawing_id = ?
                ORDER BY member_id ASC
                """,
                Long.class,
                drawingId
        );
    }
}
