package kr.co.cking.creator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Creator Space 배너 이미지의 기본값·URL 수정·업로드 연결·복귀 규칙(이슈 #590). */
class CreatorSpaceBannerImageTest {

    private CreatorSpace space;

    @BeforeEach
    void setUp() {
        space = CreatorSpaceFixtures.space(42L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-42");
    }

    @Test
    void 생성하면_템플릿_배너가_현재_배너이자_기본_배너다() {
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/banner.png");
        assertThat(space.getDefaultBannerImageUrl()).isEqualTo("https://img/banner.png");
        assertThat(space.getBannerImageKey()).isNull();
    }

    @Test
    void 업로드_배너가_없으면_URL_수정이_현재_배너와_기본_배너를_함께_바꾼다() {
        space.updateProfile("새 소개", "https://img/p2.png", "https://img/b2.png");

        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/b2.png");
        assertThat(space.getDefaultBannerImageUrl()).isEqualTo("https://img/b2.png");
    }

    @Test
    void 업로드_배너가_연결돼_있으면_URL_수정은_배너를_바꾸지_않는다() {
        space.updateBannerImage("creator-space-profile-images/42/banner/a.jpg", "https://cdn/a.jpg");

        space.updateProfile("새 소개", "https://img/p2.png", "https://img/b2.png");

        assertThat(space.getBannerImageKey()).isEqualTo("creator-space-profile-images/42/banner/a.jpg");
        assertThat(space.getBannerImageUrl()).isEqualTo("https://cdn/a.jpg");
        assertThat(space.getDefaultBannerImageUrl()).isEqualTo("https://img/banner.png");
        assertThat(space.getIntroText()).isEqualTo("새 소개");
    }

    @Test
    void 기본_배너로_복귀하면_키가_사라지고_업로드_전의_기본_배너로_돌아간다() {
        space.updateBannerImage("creator-space-profile-images/42/banner/a.jpg", "https://cdn/a.jpg");

        space.restoreDefaultBannerImage();

        assertThat(space.getBannerImageKey()).isNull();
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/banner.png");
    }

    @Test
    void 배너를_바꿔도_프로필_사진은_영향받지_않는다() {
        space.updateProfileImage("creator-space-profile-images/42/p.jpg", "https://cdn/p.jpg");

        space.updateBannerImage("creator-space-profile-images/42/banner/a.jpg", "https://cdn/a.jpg");
        space.restoreDefaultBannerImage();

        assertThat(space.getProfileImageKey()).isEqualTo("creator-space-profile-images/42/p.jpg");
        assertThat(space.getProfileImageUrl()).isEqualTo("https://cdn/p.jpg");
    }
}
