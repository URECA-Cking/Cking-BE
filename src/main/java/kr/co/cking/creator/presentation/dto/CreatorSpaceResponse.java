package kr.co.cking.creator.presentation.dto;

import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.CreatorSpace;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class CreatorSpaceResponse {

    private CreatorSpaceResponse() {
    }

    /** 공개 목록 항목. openEventCount는 진행 중(IN_PROGRESS) 이벤트 수이며 없으면 0이다. slug 변경 이력은 본인에게만 필요하므로 담지 않는다. */
    public record Detail(
            Long creatorId,
            String creatorName,
            String slug,
            String introText,
            String profileImageUrl,
            String bannerImageUrl,
            long openEventCount
    ) {
        public static Detail from(CreatorSpaceView view, long openEventCount) {
            CreatorSpace space = view.space();
            return new Detail(
                    space.getCreatorId(), view.creatorName(), space.getSlug(), space.getIntroText(),
                    space.getProfileImageUrl(), space.getBannerImageUrl(), openEventCount
            );
        }
    }

    /**
     * 단건 공개 조회 응답. 목록 항목(Detail)과 달리 팔로워 수를 담는다.
     * 목록은 항목마다 집계가 늘어나므로 포함하지 않는다.
     */
    public record Profile(
            Long creatorId,
            String creatorName,
            String slug,
            String introText,
            String profileImageUrl,
            String bannerImageUrl,
            long followerCount
    ) {
        public static Profile from(CreatorSpaceView view, long followerCount) {
            CreatorSpace space = view.space();
            return new Profile(
                    space.getCreatorId(), view.creatorName(), space.getSlug(), space.getIntroText(),
                    space.getProfileImageUrl(), space.getBannerImageUrl(), followerCount
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
