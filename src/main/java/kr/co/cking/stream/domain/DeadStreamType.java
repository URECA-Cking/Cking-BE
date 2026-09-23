package kr.co.cking.stream.domain;

/**
 * {@code dead_stream_message.stream_type}. 어느 Stream에서 실패했는지 구분한다
 * (DB 스키마 찐 최종 §25 — EARN/SPEND를 하나의 그룹으로 묶지 않는다는 전제).
 */
public enum DeadStreamType {
    EARN,
    SPEND,
    // 공용(크리에이터 무관) 미션 EARN. 크리에이터 EARN(위 EARN)과 Stream·Consumer Group이
    // 달라 값을 공유하지 않는다(이슈 #244, #219/#224에서 범위 밖으로 미뤄뒀던 부분).
    COMMON_EARN
}
