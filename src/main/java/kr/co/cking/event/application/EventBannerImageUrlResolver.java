package kr.co.cking.event.application;

import java.net.URI;
import java.util.Objects;
import kr.co.cking.event.application.config.EventBannerImageProperties;
import org.springframework.stereotype.Component;

/** Event 배너 Object key를 만료되지 않는 CDN 공개 URL로 바꾼다. */
@Component
public class EventBannerImageUrlResolver {

    private final URI publicBaseUri;

    public EventBannerImageUrlResolver(EventBannerImageProperties properties) {
        String configured = Objects.requireNonNull(properties.publicBaseUrl(), "cking.event.banner-image.public-base-url");
        if (configured.isBlank()) {
            throw new IllegalArgumentException("cking.event.banner-image.public-base-url must not be blank");
        }
        this.publicBaseUri = URI.create(configured.endsWith("/") ? configured : configured + "/");
    }

    public String resolve(String objectKey) {
        return objectKey == null ? null : publicBaseUri.resolve(objectKey).toString();
    }
}
