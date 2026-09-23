package kr.co.cking.ticket.domain;

/**
 * 응모권 종류(이슈 #243). 이벤트가 아니라 **응모 요청**이 갖는 속성이다 — 같은 이벤트에
 * 어떤 사용자는 CREATOR로, 어떤 사용자는 COMMON으로 응모할 수 있다(2026-09-23 팀 확정,
 * 자비 재확인). 요청에서 생략되면 CREATOR로 취급한다.
 */
public enum CouponType {
    CREATOR,
    COMMON
}
