package kr.co.cking.creator.presentation.dto;

import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.CreatorSpace;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class CreatorSpaceResponse {

    private CreatorSpaceResponse() {
    }

    public record Detail(
            Long creatorId,
            String creatorName,
            String slug,
            String introText,
            String profileImageUrl,
            String bannerImageUrl,
            Instant slugChangeableAt
    ) {
        public static Detail from(CreatorSpaceView view) {
            CreatorSpace space = view.space();
            LocalDateTime changeableAt = space.slugChangeableAt();
            return new Detail(
                    space.getCreatorId(), view.creatorName(), space.getSlug(), space.getIntroText(),
                    space.getProfileImageUrl(), space.getBannerImageUrl(),
                    changeableAt == null ? null : changeableAt.toInstant(ZoneOffset.UTC)
            );
        }
    }
}
