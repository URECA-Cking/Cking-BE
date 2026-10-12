package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CreatorSpaceProfileImageManagerTest {

    @Test
    void null로_명시하면_기본_이미지로_복귀하고_기존_객체는_삭제_대기로_전환한다() {
        CreatorSpaceProfileImageRepository images = org.mockito.Mockito.mock(CreatorSpaceProfileImageRepository.class);
        Creator creator = new Creator(7L, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", 42L);
        CreatorSpace space = CreatorSpace.fromTemplate(42L,
                new CreatorSpaceTemplate(1L, "소개", "https://img/default.jpg", "https://img/banner.jpg", "creator-{creatorId}"),
                "creator-42");
        space.updateProfileImage("creator-space-profile-images/42/current.jpg", "https://cdn/current.jpg");
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        CreatorSpaceProfileImageManager manager = new CreatorSpaceProfileImageManager(
                images, new CreatorSpaceProfileImageUrlResolver(new CreatorSpaceProfileImageProperties("https://cdn.example")),
                Clock.fixed(now, ZoneOffset.UTC));

        manager.apply(creator, space, null, true);

        assertThat(space.getProfileImageKey()).isNull();
        assertThat(space.getProfileImageUrl()).isEqualTo("https://img/default.jpg");
        then(images).should().markDeletePending("creator-space-profile-images/42/current.jpg", 42L, now);
    }
}
