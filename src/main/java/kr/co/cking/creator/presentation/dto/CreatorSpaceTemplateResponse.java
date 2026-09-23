package kr.co.cking.creator.presentation.dto;

import kr.co.cking.creator.domain.CreatorSpaceTemplate;

import java.time.Instant;
import java.time.ZoneOffset;

public final class CreatorSpaceTemplateResponse {

    private CreatorSpaceTemplateResponse() {
    }

    public record Detail(
            Long templateId,
            String introText,
            String profileImageUrl,
            String bannerImageUrl,
            String slugRule,
            boolean homeTabEnabled,
            boolean missionsTabEnabled,
            boolean postsTabEnabled,
            boolean eventsTabEnabled,
            boolean active,
            Long createdBy,
            Long updatedBy,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static Detail from(CreatorSpaceTemplate template) {
            return new Detail(
                    template.getTemplateId(), template.getIntroText(), template.getProfileImageUrl(),
                    template.getBannerImageUrl(), template.getSlugRule(), template.isHomeTabEnabled(),
                    template.isMissionsTabEnabled(), template.isPostsTabEnabled(), template.isEventsTabEnabled(),
                    template.isActive(), template.getCreatedBy(), template.getUpdatedBy(),
                    template.getCreatedAt().toInstant(ZoneOffset.UTC), template.getUpdatedAt().toInstant(ZoneOffset.UTC)
            );
        }
    }
}
