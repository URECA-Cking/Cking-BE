package kr.co.cking.event.application.config;

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

    public static String balance(Long creatorId, Long userId) {
        return "ticket:balance:" + creatorId + ":" + userId;
    }

    public static String idem(String requestId) {
        return "idem:" + requestId;
    }

    public static String cutoff(Long eventId) {
        return "event:cutoff:" + eventId;
    }
}
