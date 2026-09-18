package kr.co.cking.event.application.dto;

import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;

// entry-spend.lua 실행 결과.
// - SUCCESS: streamId, balance 모두 있음
// - DUPLICATE_REPLAY: idem 경로면 둘 다 있고, guard 경로면 둘 다 없음
// - INSUFFICIENT_BALANCE: balance만 있음
public record EntrySpendResult(
        EntrySpendResultCode code,
        String streamId,
        Long balance
) {

    public static EntrySpendResult of(EntrySpendResultCode code) {
        return new EntrySpendResult(code, null, null);
    }

    public static EntrySpendResult ofBalance(EntrySpendResultCode code, Long balance) {
        return new EntrySpendResult(code, null, balance);
    }

    public static EntrySpendResult ofSuccess(EntrySpendResultCode code, String streamId, Long balance) {
        return new EntrySpendResult(code, streamId, balance);
    }
}
