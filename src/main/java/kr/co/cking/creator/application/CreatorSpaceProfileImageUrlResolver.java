package kr.co.cking.creator.application;

import java.net.URI;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Creator Space Object key를 만료되지 않는 CDN 공개 URL로 바꾼다. */
@Component
public class CreatorSpaceProfileImageUrlResolver {

    private final URI publicBaseUri;

    public CreatorSpaceProfileImageUrlResolver(CreatorSpaceProfileImageProperties properties) {
        String configured = Objects.requireNonNull(
                properties.publicBaseUrl(), "cking.creator.space-profile-image.public-base-url");
        if (configured.isBlank()) {
            throw new IllegalArgumentException("cking.creator.space-profile-image.public-base-url must not be blank");
        }
        this.publicBaseUri = URI.create(configured.endsWith("/") ? configured : configured + "/");
    }

    public String resolve(String objectKey) {
        return objectKey == null ? null : publicBaseUri.resolve(objectKey).toString();
    }
}
