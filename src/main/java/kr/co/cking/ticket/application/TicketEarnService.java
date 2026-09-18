package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnResult;

/**
 * EARN 적립 진입점. 미션 완료(T1) 쪽은 이 인터페이스만 호출한다.
 *
 * <p>멱등성 확인(FR-P1-017) + 중복 적립 가드(FR-P2-006) + Redis Balance 증가 +
 * {@code stream:ticket-earned} 발행까지가 성공 기준이며 구현 책임은 이 도메인(T2-01c)이
 * 가진다 — Consumer의 DB commit은 성공 기준에 포함하지 않는다. Consumer 등록·재기동
 * 이어받기 자체는 별도 작업(T2-04)이 담당한다. {@link EarnResultCode} 6종을 모두 반환한다
 * (이슈 #30, 성집·자비 2026-09-16 합의로 가드까지 {@code ticket-earn.lua}에 통합).
 */
public interface TicketEarnService {

    EarnResult earn(EarnCommand command);

    /**
     * {@code earn()}을 실행하지 않는 replay 조회다. 호출 측은 활성 상태 검증보다 먼저
     * 조회하며, 최종 판정은 {@code earn()}의 원자적 Lua 처리에 맡긴다.
     */
    EarnLookupResult findExisting(EarnCommand command);
}
