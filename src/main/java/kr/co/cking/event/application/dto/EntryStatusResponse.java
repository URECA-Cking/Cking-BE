package kr.co.cking.event.application.dto;

// 실시간 응모 현황(FR-P2-045~050) 응답. 표시용 값이며 응모 승인·추첨의 근거로 쓰지 않는다.
// realtime=true면 Redis 집계(응모 수락 기준), false면 DB event_entry 집계(Consumer 반영
// 기준, CLOSED 이후는 Drain이 끝난 확정값)다. myTicketCount는 인증된 사용자의 사용 응모권 수다.
public record EntryStatusResponse(
        Long eventId,
        long participantCount,
        long totalTicketCount,
        Long myTicketCount,
        boolean realtime
) {
}
