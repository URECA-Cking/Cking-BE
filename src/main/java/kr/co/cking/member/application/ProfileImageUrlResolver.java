package kr.co.cking.member.application;

import java.net.URI;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Object key를 만료되지 않는 CDN 공개 URL로 바꾼다. */
@Component
public class ProfileImageUrlResolver {

    private final URI publicBaseUri;

    public ProfileImageUrlResolver(MemberProfileImageProperties properties) {
        String configured = Objects.requireNonNull(properties.publicBaseUrl(), "cking.member.profile-image.public-base-url");
        if (configured.isBlank()) {
            throw new IllegalArgumentException("cking.member.profile-image.public-base-url must not be blank");
        }
        this.publicBaseUri = URI.create(configured.endsWith("/") ? configured : configured + "/");
    }

    public String resolve(String objectKey) {
        return objectKey == null ? null : publicBaseUri.resolve(objectKey).toString();
    }
}
