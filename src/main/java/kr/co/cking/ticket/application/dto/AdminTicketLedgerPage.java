package kr.co.cking.ticket.application.dto;

import java.util.List;

import kr.co.cking.ticket.domain.CouponType;

/** 관리자가 Creator별·공용 응모권 이력을 같은 형식으로 조회할 때 사용하는 응답이다. */
public record AdminTicketLedgerPage(
        Long memberId,
        CouponType couponType,
        Long creatorId,
        List<TicketLedgerItemResponse> items,
        String nextCursor,
        boolean hasNext
) {

    /** Creator 전용 응모권 이력 조회 결과를 관리자 응답 형식으로 변환한다. */
    public static AdminTicketLedgerPage from(TicketLedgerPage page) {
        return new AdminTicketLedgerPage(page.userId(), CouponType.CREATOR, page.creatorId(), page.items(),
                page.nextCursor(), page.hasNext());
    }

    /** 공용 응모권 이력 조회 결과를 관리자 응답 형식으로 변환한다. */
    public static AdminTicketLedgerPage from(CommonTicketLedgerPage page) {
        return new AdminTicketLedgerPage(page.userId(), CouponType.COMMON, null,
                page.items().stream().map(AdminTicketLedgerPage::toTicketLedgerItem).toList(),
                page.nextCursor(), page.hasNext());
    }

    /** 공용 원장 항목을 Creator 전용 원장과 공통으로 쓰는 응답 항목으로 변환한다. */
    private static TicketLedgerItemResponse toTicketLedgerItem(CommonTicketLedgerItemResponse item) {
        return new TicketLedgerItemResponse(item.ledgerId(), item.deltaAmount(), item.type(), item.missionId(),
                item.eventId(), item.reason(), item.requestId(), item.createdAt());
    }
}
