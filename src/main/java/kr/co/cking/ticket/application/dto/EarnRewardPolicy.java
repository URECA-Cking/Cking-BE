package kr.co.cking.ticket.application.dto;

/** Creator 미션 EARN의 중복 적립 주기와 DB 완료 키 정책을 정의한다. */
public enum EarnRewardPolicy {
    DAILY,
    ONCE;

    /** 완료 이력의 DB Business Key에 사용할 값을 만든다. */
    public String completionKey(String periodKey) {
        return this == ONCE ? "ONCE" : periodKey;
    }

    /** Redis Guard가 만료 없이 유지되어야 하는 평생 1회 정책인지 판별한다. */
    public boolean isOnce() {
        return this == ONCE;
    }
}
