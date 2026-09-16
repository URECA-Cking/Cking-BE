package kr.co.cking.entry;

/**
 * Redis Gate/Lua 응모 원자 처리 진입점. Gate 확인→잔액 확인→차감→Entry Stream XADD까지의
 * 실제 구현은 T2-03(Redis Lua 동시성 처리)이 담당한다.
 */
public interface EntryGateway {

    EntryResultCode apply(Long eventId, Long creatorId, EntryRequest request);
}
