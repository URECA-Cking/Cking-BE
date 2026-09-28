package kr.co.cking.mission.domain;

/** Creator 미션 유형. DB {@code mission.type} 컬럼 값과 1:1 대응한다. */
public enum MissionType {
    ATTENDANCE,
    LIKE,
    SHARE,
    YOUTUBE_SUBSCRIPTION
}
