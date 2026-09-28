package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CreatorSpaceProfileServiceTest {

    private static final CreatorSpaceProfileFields NEW_PROFILE = new CreatorSpaceProfileFields(
            "새 소개", "https://img/p2.png", "https://img/b2.png", true, false, true, false
    );

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
    private final CreatorSpaceProfileService service = new CreatorSpaceProfileService(creatorRepository, spaceRepository);

    private Creator creator;
    private CreatorSpace space;

    @BeforeEach
    void setUp() {
        creator = new Creator(7L, "크리에이터");
        ReflectionTestUtils.setField(creator, "creatorId", 42L);
        space = CreatorSpace.fromTemplate(42L, new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}",
                true, true, true, true
        ), "creator-42");
    }

    @Test
    void findByCreatorIdReturnsSpaceWithCreatorName() {
        given(creatorRepository.findById(42L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.of(space));

        CreatorSpaceView view = service.findByCreatorId(42L);

        assertThat(view.space()).isSameAs(space);
        assertThat(view.creatorName()).isEqualTo("크리에이터");
    }

    @Test
    void findByCreatorIdThrowsNotFoundWhenCreatorMissing() {
        given(creatorRepository.findById(42L)).willReturn(Optional.empty());

        assertErrorCode(() -> service.findByCreatorId(42L), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    /** V19 백필이 건너뛰어진 기존 Creator처럼 Space가 없으면 404다. */
    @Test
    void findByCreatorIdThrowsNotFoundWhenSpaceMissing() {
        given(creatorRepository.findById(42L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.empty());

        assertErrorCode(() -> service.findByCreatorId(42L), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void findBySlugReturnsSpaceWithCreatorName() {
        given(spaceRepository.findBySlug("creator-42")).willReturn(Optional.of(space));
        given(creatorRepository.findById(42L)).willReturn(Optional.of(creator));

        CreatorSpaceView view = service.findBySlug("creator-42");

        assertThat(view.space()).isSameAs(space);
        assertThat(view.creatorName()).isEqualTo("크리에이터");
    }

    @Test
    void findBySlugThrowsNotFoundWhenSpaceMissing() {
        given(spaceRepository.findBySlug("missing")).willReturn(Optional.empty());

        assertErrorCode(() -> service.findBySlug("missing"), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void findMineResolvesCreatorByMemberId() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.of(space));

        assertThat(service.findMine(7L).space()).isSameAs(space);
    }

    @Test
    void findMineThrowsForbiddenWhenCallerIsNotCreator() {
        given(creatorRepository.findByMemberId(8L)).willReturn(Optional.empty());

        assertErrorCode(() -> service.findMine(8L), CommonErrorCode.FORBIDDEN);
    }

    @Test
    void updateMineChangesOwnSpaceProfile() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.of(space));

        CreatorSpaceView view = service.updateMine(7L, NEW_PROFILE);

        assertThat(view.space().getIntroText()).isEqualTo("새 소개");
        assertThat(view.space().isMissionsTabEnabled()).isFalse();
        assertThat(view.space().getSlug()).isEqualTo("creator-42");
    }

    @Test
    void updateMineThrowsForbiddenWhenCallerIsNotCreator() {
        given(creatorRepository.findByMemberId(8L)).willReturn(Optional.empty());

        assertErrorCode(() -> service.updateMine(8L, NEW_PROFILE), CommonErrorCode.FORBIDDEN);
    }

    private void assertErrorCode(Runnable call, CommonErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
