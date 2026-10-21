package kr.co.cking.event.application.dto;

/** 공개 이벤트의 표시 상태별 총 개수. total = upcoming + inProgress + closed. */
public record EventCounts(long total, long upcoming, long inProgress, long closed) {
}
