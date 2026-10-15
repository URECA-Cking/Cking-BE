package kr.co.cking.creator.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record RecommendationTrackingSettings(Duration requestTtl, Duration attributionWindow, Duration retention) {
    public RecommendationTrackingSettings(
            @Value("${cking.recommendation.tracking.request-ttl:PT24H}") Duration requestTtl,
            @Value("${cking.recommendation.tracking.attribution-window:PT24H}") Duration attributionWindow,
            @Value("${cking.recommendation.tracking.retention:P90D}") Duration retention) {
        if (requestTtl == null || attributionWindow == null || retention == null
                || requestTtl.isNegative() || requestTtl.isZero()
                || attributionWindow.isNegative() || attributionWindow.isZero()
                || attributionWindow.getNano() != 0 || attributionWindow.toSeconds() < 1
                || retention.compareTo(requestTtl.plus(attributionWindow)) < 0) {
            throw new IllegalArgumentException("Recommendation retention must cover positive request/attribution windows");
        }
        this.requestTtl = requestTtl;
        this.attributionWindow = attributionWindow;
        this.retention = retention;
    }
}
