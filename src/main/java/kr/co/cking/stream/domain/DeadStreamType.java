package kr.co.cking.stream.domain;

/**
 * {@code dead_stream_message.stream_type}. 어느 Stream에서 실패했는지 구분한다
 * (DB 스키마 찐 최종 §25 — EARN/SPEND를 하나의 그룹으로 묶지 않는다는 전제).
 */
public enum DeadStreamType {
    EARN,
    SPEND
}
