package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnResult;

/**
 * EARN 적립 진입점. 미션 완료(T1) 쪽은 이 인터페이스만 호출한다.
 *
 * <p>Redis Balance 증가 + {@code stream:ticket-earned} 발행까지가 성공 기준이며
 * 구현 책임은 이 도메인(T2-01c)이 가진다 — Consumer의 DB commit은 성공 기준에 포함하지 않는다.
 * Consumer 등록·재기동 이어받기 자체는 별도 작업(T2-04)이 담당한다.
 * Business Key({@code userId+creatorId+missionId+periodKey}) 중복 판정 가드는
 * 이슈 #30(자비님)에서 별도로 진행 중이라, {@link TicketEarnServiceImpl}은 그 가드
 * 없이 Balance 증가 + Stream 발행만 구현돼 있다 — {@code ALREADY_PROCESSED}/
 * {@code DUPLICATE_MISSION}/{@code REQUEST_ID_CONFLICT}는 가드가 붙기 전까지 반환되지 않는다.
 */
public interface TicketEarnService {

    EarnResult earn(EarnCommand command);
}
