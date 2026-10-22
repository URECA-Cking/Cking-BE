package kr.co.cking.event.application.dto;

import kr.co.cking.event.domain.PrizeConfig;

/** 외부 조회 응답에 노출하는 상품 등급 설정이다. */
public record PrizeResult(String prizeKey, String displayName, int priority, long weight, int quantity) {
    public static PrizeResult from(PrizeConfig config) {
        return new PrizeResult(config.prizeKey(), config.displayName(), config.priority(), config.weight(), config.quantity());
    }
}
