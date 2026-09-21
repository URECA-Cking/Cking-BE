package kr.co.cking.event.domain;

/** Event와 공식 Snapshot 사이에서 상품 등급 설정을 전달하는 불변 값이다. */
public record PrizeConfig(
        String prizeKey,
        String displayName,
        int priority,
        long weight,
        int quantity
) implements java.io.Serializable {

    public PrizeConfig {
        if (prizeKey == null || !prizeKey.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("prizeKey는 영문, 숫자, _, -로 이루어진 1~100자여야 합니다.");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 200
                || displayName.indexOf('\n') >= 0 || displayName.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("displayName은 줄바꿈 없는 1~200자여야 합니다.");
        }
        if (priority <= 0) {
            throw new IllegalArgumentException("priority는 양수여야 합니다.");
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("weight는 양수여야 합니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다.");
        }
    }
}
