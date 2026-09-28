package kr.co.cking.creator.presentation.dto;

import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.CreatorSpace;

public final class CreatorSpaceResponse {

    private CreatorSpaceResponse() {
    }

    public record Detail(
            Long creatorId,
            String creatorName,
            String slug,
            String introText,
            String profileImageUrl,
            String bannerImageUrl
    ) {
        public static Detail from(CreatorSpaceView view) {
            CreatorSpace space = view.space();
            return new Detail(
                    space.getCreatorId(), view.creatorName(), space.getSlug(), space.getIntroText(),
                    space.getProfileImageUrl(), space.getBannerImageUrl()
            );
        }
    }
}
