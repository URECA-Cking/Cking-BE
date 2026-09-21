package kr.co.cking.ticket.application.config;

// ticket-earn.lua(EARN)와 향후 ticket 도메인 Lua가 참조하는 Redis 키 포맷.
public final class TicketRedisKeys {

    private TicketRedisKeys() {
    }

    public static String balance(Long creatorId, Long userId) {
        return "ticket:balance:" + creatorId + ":" + userId;
    }

    // SPEND의 idem:{requestId}와 분리된 미션 적립 replay 키다.
    public static String idemMission(String requestId) {
        return "idem:mission:" + requestId;
    }

    // FR-P2-006 확정 포맷. periodKeyYyyyMmDd는 yyyyMMdd(대시 없음) - EarnCommand.periodKey()의
    // YYYY-MM-DD와는 다른 표기이니 호출측에서 변환해서 넘겨야 한다.
    public static String earnGuard(Long userId, String missionType, Long creatorId, String periodKeyYyyyMmDd) {
        return "mission:earn-guard:" + userId + ":" + missionType + ":" + creatorId + ":" + periodKeyYyyyMmDd;
    }

    // 수동 보정 중 SPEND·EARN을 막는 maintenance lock. Balance 키와 같은 (creatorId, userId) 순서다.
    // TicketMaintenanceLock(issue #174/PR #176)이 SET/DEL하고, entry-spend.lua/ticket-earn.lua는
    // EXISTS만 본다(issue #172). 메서드명은 두 PR이 같은 키를 가리키도록 #176 쪽과 통일했다.
    public static String maintenance(Long creatorId, Long userId) {
        return "ticket:maint:" + creatorId + ":" + userId;
    }
}
