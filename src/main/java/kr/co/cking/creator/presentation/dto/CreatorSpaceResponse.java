package kr.co.cking.creator.presentation.dto;

import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.CreatorSpace;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class CreatorSpaceResponse {

    private CreatorSpaceResponse() {
    }

    /** 공개 조회 응답. slug 변경 이력은 본인에게만 필요하므로 담지 않는다. */
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

    /** 본인 조회·수정 응답. 화면에서 "N일 후 변경 가능"을 보여줄 수 있게 다음 slug 변경 가능 시각을 담는다. */
    public record Mine(
            Long creatorId,
            String creatorName,
            String slug,
            String introText,
            String profileImageUrl,
            String bannerImageUrl,
            Instant slugChangeableAt
    ) {
        public static Mine from(CreatorSpaceView view) {
            CreatorSpace space = view.space();
            LocalDateTime changeableAt = space.slugChangeableAt();
            return new Mine(
                    space.getCreatorId(), view.creatorName(), space.getSlug(), space.getIntroText(),
                    space.getProfileImageUrl(), space.getBannerImageUrl(),
                    changeableAt == null ? null : changeableAt.toInstant(ZoneOffset.UTC)
            );
        }
    }
}
