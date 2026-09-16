package kr.co.cking.event.application.dto;

import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;

// entry-spend.lua 실행 결과. streamId/balance는 code에 따라 없을 수 있다.
// - SUCCESS, DUPLICATE_REPLAY: streamId, balance 모두 있음
// - INSUFFICIENT_BALANCE: balance만 있음
// - 그 외: 둘 다 null
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
