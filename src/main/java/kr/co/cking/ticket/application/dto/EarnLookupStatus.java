package kr.co.cking.ticket.application.dto;

import kr.co.cking.ticket.application.TicketEarnService;

/**
 * {@link TicketEarnService#findExisting} 결과. {@code earn()}을 실행하지 않는
 * read-only 조회이므로 {@link EarnResultCode}와는 별개 타입이다.
 */
public enum EarnLookupStatus {
    // 동일 requestId·동일 fingerprint의 완료된 요청이 있다. 활성 검증 없이 기존
    // 성공 결과를 반환해야 한다.
    ALREADY_PROCESSED,
    // 동일 requestId에 다른 요청 내용(fingerprint)이 왔다.
    REQUEST_ID_CONFLICT,
    // TTL 내 replay 기록이 없다. 신규 요청으로 간주하고 활성 검증 후 earn()을 호출한다.
    NOT_FOUND,
    // Redis 조회 실패, 또는 idem이 PROCESSING 상태(이전 시도가 Balance/Stream
    // 처리 뒤 확정 전에 죽어 결과를 알 수 없는 상태)라 신규 지급으로 진행하면 안
    // 된다. 두 경우 모두 호출측 처리(신규 지급 금지, 시스템 오류 반환)가 같아
    // 하나의 상태로 합쳤다.
    UNAVAILABLE
}
