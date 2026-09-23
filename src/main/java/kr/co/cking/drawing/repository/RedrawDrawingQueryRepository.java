package kr.co.cking.drawing.repository;

import java.util.List;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** REDRAW Drawing 생성·검증에 필요한 Winner와 Redraw 원본을 읽는 전용 조회 경계다. */
@Repository
@RequiredArgsConstructor
public class RedrawDrawingQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    /** REDRAW 후보 제외와 제외 명단 보존에 필요한 Event의 기존 Winner를 읽는다. */
    public List<RedrawExclusionSource> findExclusionSourcesByEventId(Long eventId) {
        return jdbcTemplate.query(
                """
                SELECT w.member_id, management.status AS management_status
                FROM winner w
                LEFT JOIN winner_management management ON management.winner_id = w.id
                WHERE w.event_id = ?
                ORDER BY w.member_id ASC
                """,
                (resultSet, rowNum) -> {
                    String managementStatus = resultSet.getString("management_status");
                    return new RedrawExclusionSource(
                            resultSet.getLong("member_id"),
                            managementStatus == null ? null : WinnerManagementStatus.valueOf(managementStatus)
                    );
                },
                eventId
        );
    }

    /** RedrawRequest가 고정한 결원 Winner의 상품을 결원 확정 순서대로 읽는다. */
    public List<RedrawVacancyPrizeSource> findVacancyPrizeSourcesByRequestId(Long redrawRequestId) {
        return jdbcTemplate.query(
                """
                SELECT w.id AS winner_id, w.snapshot_prize_id
                FROM redraw_request_vacancy vacancy
                JOIN winner w ON w.id = vacancy.winner_id
                WHERE vacancy.redraw_request_id = ?
                ORDER BY vacancy.id ASC
                """,
                (resultSet, rowNum) -> new RedrawVacancyPrizeSource(
                        resultSet.getLong("winner_id"),
                        resultSet.getObject("snapshot_prize_id", Long.class)
                ),
                redrawRequestId
        );
    }
}
