package kr.co.cking.ticket.application.dto;

/** SPEND 메시지를 DB에 반영한 결과. */
public enum SpendApplyResult {

    /** 새로 반영했다. */
    APPLIED,

    /** 같은 메시지가 다시 전달됐거나, 반영한 메시지를 알 수 없는 기존 응모다. */
    ALREADY_APPLIED,

    /** 이미 반영된 requestId가 새 메시지로 왔다. 응모 Lua가 다시 차감했으므로 Redis에서 되돌려야 한다(#673). */
    REDEDUCTED
}
