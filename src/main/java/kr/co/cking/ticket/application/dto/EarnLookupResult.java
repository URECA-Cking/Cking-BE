package kr.co.cking.ticket.application.dto;

/** 조회 상태와 동일 Fingerprint로 확인된 영속 보상 선점 여부를 전달한다. */
public record EarnLookupResult(EarnLookupStatus status, boolean persistedRequest) {
    public EarnLookupResult(EarnLookupStatus status) {
        this(status, false);
    }
}
