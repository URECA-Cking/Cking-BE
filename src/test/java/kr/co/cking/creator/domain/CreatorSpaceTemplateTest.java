package kr.co.cking.creator.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorSpaceTemplateTest {

    @Test
    void newTemplateIsInactiveAndTracksCreator() {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"
        );

        assertThat(template.isActive()).isFalse();
        assertThat(template.getActiveMarker()).isNull();
        assertThat(template.getCreatedBy()).isEqualTo(1L);
        assertThat(template.getUpdatedBy()).isEqualTo(1L);
    }

    @Test
    void activateSetsMarkerAndDeactivateClearsIt() {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"
        );

        template.activate(2L);
        assertThat(template.isActive()).isTrue();
        assertThat(template.getActiveMarker()).isEqualTo(CreatorSpaceTemplate.ACTIVE_MARKER);
        assertThat(template.getUpdatedBy()).isEqualTo(2L);

        template.deactivate(3L);
        assertThat(template.isActive()).isFalse();
        assertThat(template.getActiveMarker()).isNull();
        assertThat(template.getUpdatedBy()).isEqualTo(3L);
    }

    @Test
    void updateReplacesAllFieldsAndTracksModifier() {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"
        );

        template.update(9L, "새 소개", "https://img/new-profile.png", "https://img/new-banner.png", "new-{creatorId}");

        assertThat(template.getIntroText()).isEqualTo("새 소개");
        assertThat(template.getProfileImageUrl()).isEqualTo("https://img/new-profile.png");
        assertThat(template.getBannerImageUrl()).isEqualTo("https://img/new-banner.png");
        assertThat(template.getSlugRule()).isEqualTo("new-{creatorId}");
        assertThat(template.getUpdatedBy()).isEqualTo(9L);
    }
}
