package kr.co.cking.event.application.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 목록 캐시 저장 단위. {@link CachedEvent}처럼 원본 필드만 담고 {@code displayStatus}는
 * 굽지 않는다 — 읽는 시점의 now로 매번 다시 계산해서, 캐시에 적중해도 TTL 안에서
 * displayStatus가 굳어 보이지 않게 한다(상세 캐시 {@code EventDetail.of}와 동일한 이유).
 */
public record CachedEventPage(List<CachedEvent> events, long totalElements) implements Serializable {

    public CachedEventPage {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
