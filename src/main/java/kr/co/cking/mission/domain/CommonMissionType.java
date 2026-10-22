package kr.co.cking.mission.domain;

/**
 * 공용 미션(크리에이터에 묶이지 않음) 유형. DB {@code common_mission.type} 컬럼
 * 값과 1:1 대응한다. 크리에이터별 {@link MissionType}과는 별개 개념이다 — "좋아요"처럼
 * 특정 크리에이터 콘텐츠를 전제하는 유형은 공용에 두지 않는다(이슈 #219).
 */
public enum CommonMissionType {
    ATTENDANCE
}
