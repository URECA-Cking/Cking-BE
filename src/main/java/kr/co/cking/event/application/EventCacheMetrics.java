package kr.co.cking.event.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class EventCacheMetrics {

    private final Counter hits;
    private final Counter misses;
    private final Timer singleFlightLoads;
    private final Timer inTransactionLoads;
    private final Timer loadWaits;

    public EventCacheMetrics(MeterRegistry registry) {
        this.hits = lookup(registry, "hit");
        this.misses = lookup(registry, "miss");
        this.singleFlightLoads = load(registry, "single_flight");
        this.inTransactionLoads = load(registry, "in_transaction");
        this.loadWaits = Timer.builder("cking.event.cache.load.wait")
                .description("다른 요청이 진행 중인 이벤트 캐시 적재를 기다린 시간")
                .register(registry);
    }

    static EventCacheMetrics detached() {
        return new EventCacheMetrics(new SimpleMeterRegistry());
    }

    void hit() {
        hits.increment();
    }

    void miss() {
        misses.increment();
    }

    <T> T singleFlightLoad(Supplier<T> load) {
        return singleFlightLoads.record(load);
    }

    <T> T inTransactionLoad(Supplier<T> load) {
        return inTransactionLoads.record(load);
    }

    <T> T loadWait(Supplier<T> wait) {
        return loadWaits.record(wait);
    }

    private static Counter lookup(MeterRegistry registry, String result) {
        return Counter.builder("cking.event.cache.lookup")
                .description("이벤트 상세 캐시 조회 결과")
                .tag("result", result)
                .register(registry);
    }

    private static Timer load(MeterRegistry registry, String mode) {
        return Timer.builder("cking.event.cache.load")
                .description("이벤트 상세 캐시가 비어 DB를 읽은 시간")
                .tag("mode", mode)
                .register(registry);
    }
}
