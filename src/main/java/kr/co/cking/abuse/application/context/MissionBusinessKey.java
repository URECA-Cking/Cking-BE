package kr.co.cking.abuse.application.context;

/** Abuse Observation에서 사용할 Mission 업무 키와 DAILY 기간이다. ONCE의 기간은 null이다. */
public record MissionBusinessKey(String value, String periodKey) {

    public MissionBusinessKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Mission businessKey는 필수입니다.");
        }
        if (periodKey != null && periodKey.isBlank()) {
            throw new IllegalArgumentException("periodKey는 값이 있으면 비어 있을 수 없습니다.");
        }
    }
}
