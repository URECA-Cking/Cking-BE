package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceFixtures;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 배너 이미지 연결·교체·기본 배너 복귀(이슈 #590). */
class CreatorSpaceBannerImageLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final String OLD_KEY = "creator-space-profile-images/42/banner/old.jpg";
    private static final String NEW_KEY = "creator-space-profile-images/42/banner/new.jpg";

    private final CreatorRepository creators = mock(CreatorRepository.class);
    private final CreatorSpaceRepository spaces = mock(CreatorSpaceRepository.class);
    private final CreatorSpaceProfileImageRepository images = mock(CreatorSpaceProfileImageRepository.class);
    private final CreatorSpaceProfileImageManager manager = new CreatorSpaceProfileImageManager(
            images, new CreatorSpaceProfileImageUrlResolver(new CreatorSpaceProfileImageProperties("https://cdn.example")),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final CreatorSpaceProfileImageLinkService service = new CreatorSpaceProfileImageLinkService(creators, spaces, manager);

    private CreatorSpace space;
    private Creator creator;

    @BeforeEach
    void setUp() {
        creator = new Creator(7L, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", 42L);
        space = CreatorSpaceFixtures.space(42L, "소개", "https://img/profile.png", "https://img/default-banner.png", "creator-42");
        given(creators.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaces.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));
    }

    @Test
    void 본인이_배너로_올린_완료_이미지를_연결하고_기존_배너는_삭제_대기로_바꾼다() {
        space.updateBannerImage(OLD_KEY, "https://cdn.example/" + OLD_KEY);
        given(images.linkBanner(NEW_KEY, 42L, NOW)).willReturn(1);

        service.linkBanner(7L, NEW_KEY);

        assertThat(space.getBannerImageKey()).isEqualTo(NEW_KEY);
        assertThat(space.getBannerImageUrl()).isEqualTo("https://cdn.example/" + NEW_KEY);
        then(images).should().markDeletePending(OLD_KEY, 42L, NOW);
    }

    @Test
    void 처음_연결하면_삭제할_이전_배너가_없다() {
        given(images.linkBanner(NEW_KEY, 42L, NOW)).willReturn(1);

        service.linkBanner(7L, NEW_KEY);

        assertThat(space.getBannerImageKey()).isEqualTo(NEW_KEY);
        then(images).should(never()).markDeletePending(any(), any(), any());
    }

    @Test
    void 이미_연결된_같은_키_재요청은_저장소를_건드리지_않고_성공한다() {
        space.updateBannerImage(NEW_KEY, "https://cdn.example/" + NEW_KEY);

        service.linkBanner(7L, NEW_KEY);

        assertThat(space.getBannerImageKey()).isEqualTo(NEW_KEY);
        then(images).shouldHaveNoInteractions();
    }

    @Test
    void 다른_Creator의_키이거나_업로드_완료되지_않았거나_프로필로_올린_키는_배너_오류로_거부한다() {
        given(images.linkBanner(any(), eq(42L), any())).willReturn(0);

        assertThatThrownBy(() -> service.linkBanner(7L, "creator-space-profile-images/42/profile.jpg"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_BANNER_IMAGE);

        assertThat(space.getBannerImageKey()).isNull();
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/default-banner.png");
        then(images).should(never()).markDeletePending(any(), any(), any());
    }

    @Test
    void 공백_키는_저장소를_조회하지_않고_거부한다() {
        assertThatThrownBy(() -> service.linkBanner(7L, " "))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_BANNER_IMAGE);

        then(images).shouldHaveNoInteractions();
    }

    @Test
    void 키를_null로_명시하면_기본_배너로_복귀하고_기존_배너는_삭제_대기로_바꾼다() {
        space.updateBannerImage(OLD_KEY, "https://cdn.example/" + OLD_KEY);

        manager.applyBanner(creator, space, null, true);

        assertThat(space.getBannerImageKey()).isNull();
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/default-banner.png");
        then(images).should().markDeletePending(OLD_KEY, 42L, NOW);
    }

    @Test
    void 키를_생략하면_현재_배너를_유지한다() {
        space.updateBannerImage(OLD_KEY, "https://cdn.example/" + OLD_KEY);

        manager.applyBanner(creator, space, null, false);

        assertThat(space.getBannerImageKey()).isEqualTo(OLD_KEY);
        then(images).shouldHaveNoInteractions();
    }

    @Test
    void Creator가_아닌_회원은_FORBIDDEN이다() {
        given(creators.findByMemberId(8L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.linkBanner(8L, NEW_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    void 프로필_사진_연결은_배너_키를_바꾸지_않는다() {
        given(images.link("creator-space-profile-images/42/profile.jpg", 42L, NOW)).willReturn(1);

        service.link(7L, "creator-space-profile-images/42/profile.jpg");

        assertThat(space.getProfileImageKey()).isEqualTo("creator-space-profile-images/42/profile.jpg");
        assertThat(space.getBannerImageKey()).isNull();
        then(images).should(never()).linkBanner(any(), any(), any());
    }
}
