package kr.co.cking.event.repository;

// 이벤트별 사용자당 사용 응모권 합계 프로젝션. 실시간 응모 현황(FR-P2-045~050) 집계 키를
// Gate 최초 적재 시 DB 기준으로 초기화하거나, Redis 집계가 없을 때 조회를 대체하는 데 쓴다.
public interface EventEntryAggregate {
    Long getMemberId();
    Long getTicketCount();
}
