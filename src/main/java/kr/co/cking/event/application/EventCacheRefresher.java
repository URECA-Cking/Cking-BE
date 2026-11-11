package kr.co.cking.event.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EventCacheRefresher {

    private final boolean enabled;
    private final Cache<Long, Boolean> recentLoads;
    private final Executor executor;
    private final Counter rejected;
    private final Counter failures;

    @Autowired
    public EventCacheRefresher(
            MeterRegistry registry,
            @Qualifier("eventCacheRefreshExecutor") Executor executor,
            @Value("${cking.event.cache.refresh-ahead.enabled:true}") boolean enabled,
            @Value("${cking.event.cache.refresh-ahead.after:500ms}") Duration after
    ) {
        this(registry, executor, enabled, after, Ticker.systemTicker());
    }

    EventCacheRefresher(MeterRegistry registry, Executor executor, boolean enabled, Duration after, Ticker ticker) {
        this.enabled = enabled;
        this.executor = executor;
        this.recentLoads = Caffeine.newBuilder()
                .expireAfterWrite(after)
                .ticker(ticker)
                .executor(Runnable::run)
                .build();
        this.rejected = Counter.builder("cking.event.cache.refresh.rejected")
                .description("실행기가 가득 차 버린 이벤트 캐시 미리 갱신")
                .register(registry);
        this.failures = Counter.builder("cking.event.cache.refresh.failed")
                .description("실패한 이벤트 캐시 미리 갱신")
                .register(registry);
    }

    static EventCacheRefresher disabled() {
        return new EventCacheRefresher(new SimpleMeterRegistry(), Runnable::run, false, Duration.ofMillis(500),
                Ticker.systemTicker());
    }

    boolean claim(Long eventId) {
        if (!enabled) {
            return false;
        }
        boolean[] claimed = {false};
        recentLoads.get(eventId, id -> {
            claimed[0] = true;
            return Boolean.TRUE;
        });
        return claimed[0];
    }

    void loaded(Long eventId) {
        if (enabled) {
            recentLoads.put(eventId, Boolean.TRUE);
        }
    }

    void failed() {
        failures.increment();
    }

    void submit(Runnable refresh) {
        try {
            executor.execute(refresh);
        } catch (RejectedExecutionException exception) {
            rejected.increment();
        }
    }
}
