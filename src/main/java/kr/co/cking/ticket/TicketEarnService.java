package kr.co.cking.ticket;

/**
 * EARN 적립 진입점. 미션 완료(T1) 쪽은 이 인터페이스만 호출한다.
 *
 * <p>Redis Balance 증가 + {@code stream:ticket-earned} 발행까지가 성공 기준이며
 * 구현 책임은 이 도메인(T2-01c)이 가진다 — Consumer의 DB commit은 성공 기준에 포함하지 않는다.
 * Consumer 등록·재기동 이어받기 자체는 별도 작업(T2-04)이 담당한다.
 * Business Key({@code userId+creatorId+missionId+periodKey}) 중복 판정 방식은 아직 설계 중이라
 * 구현체는 이 인터페이스가 확정된 뒤 별도로 채운다.
 */
public interface TicketEarnService {

    EarnResult earn(EarnCommand command);
}
