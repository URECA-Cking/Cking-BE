package kr.co.cking.mission.domain;

/** Creator 미션 유형. DB {@code mission.type} 컬럼 값과 1:1 대응한다. */
public enum MissionType {
    ATTENDANCE("출석체크"),
    LIKE("좋아요"),
    SHARE("공유"),
    YOUTUBE_SUBSCRIPTION("유튜브 구독");

    private final String displayName;

    MissionType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 사용자에게 보이는 미션 이름. 공용 미션({@link CommonMissionType})도 이 이름을 쓴다 — 이름 매핑은 여기 한 곳에만 둔다.
     * type이 null이거나 알 수 없는 값이면 null.
     */
    public static String displayNameOf(String type) {
        if (type == null) {
            return null;
        }
        try {
            return valueOf(type).displayName;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
