package kr.co.cking.drawing.application;

import java.time.Instant;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.winner.domain.Winner;

public record DrawingWinnerResult(
        Long winnerId,
        Long eventId,
        Long drawingId,
        Long userId,
        String name,
        String phone,
        String email,
        int rankInDrawing,
        long appliedTicketCount,
        String prizeKey,
        String prizeDisplayName,
        Integer prizePriority,
        Instant createdAt
) {

    public DrawingWinnerResult(Long winnerId, Long eventId, Long drawingId, Long userId, String name, String phone,
            String email, int rankInDrawing, long appliedTicketCount, Instant createdAt) {
        this(winnerId, eventId, drawingId, userId, name, phone, email, rankInDrawing, appliedTicketCount,
                null, null, null, createdAt);
    }

    public static DrawingWinnerResult from(Winner winner, MemberInfo member) {
        return new DrawingWinnerResult(
                winner.getId(),
                winner.getEventId(),
                winner.getDrawingId(),
                member.memberId(),
                member.name(),
                member.phone(),
                member.email(),
                winner.getRankInDrawing(),
                winner.getAppliedTicketCount(),
                winner.getPrizeKey(),
                winner.getPrizeDisplayName(),
                winner.getPrizePriority(),
                winner.getCreatedAt()
        );
    }
}
