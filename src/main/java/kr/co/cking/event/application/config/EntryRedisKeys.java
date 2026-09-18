package kr.co.cking.event.application.config;

import kr.co.cking.ticket.application.config.TicketRedisKeys;

// 응모 Lua(entry-spend.lua)가 참조하는 Redis 키 포맷.
// 데이터 구조.md §2 확정 스키마를 그대로 따른다.
public final class EntryRedisKeys {

    private EntryRedisKeys() {
    }

    public static String status(Long eventId) {
        return "event:status:" + eventId;
    }

    public static String endAt(Long eventId) {
        return "event:endat:" + eventId;
    }

    // ticket:balance 포맷은 TicketRedisKeys가 정의를 소유한다 - 여기서 별도로
    // 다시 조합하면 한쪽만 바뀔 때 조용히 어긋날 수 있다(PR #63 리뷰).
    public static String balance(Long creatorId, Long userId) {
        return TicketRedisKeys.balance(creatorId, userId);
    }

    public static String idem(String requestId) {
        return "idem:" + requestId;
    }

    public static String cutoff(Long eventId) {
        return "event:cutoff:" + eventId;
    }
}
