package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorActivityName;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceSlugReservation;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.creator.repository.CreatorSpaceSlugReservationRepository;
import kr.co.cking.event.application.EventQueryService;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;

class CreatorSpaceProfileServiceTest {

    private static final CreatorSpaceProfileFields NEW_PROFILE = new CreatorSpaceProfileFields(
            "새 소개", "https://img/p2.png", "https://img/b2.png"
    );

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 3, 0);

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
    private final CreatorSpaceSlugReservationRepository reservationRepository = mock(CreatorSpaceSlugReservationRepository.class);
    private final CreatorFollowQueryService followQueryService = mock(CreatorFollowQueryService.class);
    private final CreatorSpaceProfileImageManager profileImageManager = mock(CreatorSpaceProfileImageManager.class);
    private final CreatorSpaceTemplateImagePinner imagePinner = mock(CreatorSpaceTemplateImagePinner.class);
    private final CreatorContentOutbox contentOutbox = mock(CreatorContentOutbox.class);
    private final CreatorSpaceService spaceService = mock(CreatorSpaceService.class);
    private final CreatorSpaceProfileService service = new CreatorSpaceProfileService(
            creatorRepository, spaceRepository, new CreatorSpaceSlugRegistry(spaceRepository, reservationRepository),
            profileImageManager, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), followQueryService, mock(EventQueryService.class), imagePinner, contentOutbox, spaceService);

    private Creator creator;
    private CreatorSpace space;

    @BeforeEach
    void setUp() {
        creator = new Creator(7L, "크리에이터");
        ReflectionTestUtils.setField(creator, "creatorId", 42L);
        given(creatorRepository.findByIdForUpdate(42L)).willReturn(Optional.of(creator));
        space = CreatorSpace.fromTemplate(42L, new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"
        ), "creator-42");
    }

    @Test
    void introductionChangeQueuesTheSamePersistedRevision() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));
        service.updateMine(7L,NEW_PROFILE);
        then(spaceRepository).should().flush();
        then(contentOutbox).should().enqueue(space);
        assertThat(space.getContentRevision()).isEqualTo(2);
    }

    @Test
    void imageOnlyChangeDoesNotRequeueContent() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));
        service.updateMine(7L,new CreatorSpaceProfileFields("소개","https://img/p2.png","https://img/b2.png"));
        then(contentOutbox).shouldHaveNoInteractions();
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
    void countFollowersDelegatesToFollowQueryService() {
        given(followQueryService.countFollowers(42L)).willReturn(1280L);

        assertThat(service.countFollowers(42L)).isEqualTo(1280L);
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
    void findPublicSpacesAttachesCreatorNamesAndKeepsRepositoryOrder() {
        Creator other = new Creator(8L, "다른 크리에이터", CreatorActivityName.of("다른크리에이터"));
        ReflectionTestUtils.setField(other, "creatorId", 43L);
        CreatorSpace otherSpace = CreatorSpace.fromTemplate(43L, new CreatorSpaceTemplate(
                1L, "소개2", "https://img/profile2.png", "https://img/banner2.png", "creator-{creatorId}"
        ), "creator-43");
        PageRequest pageable = PageRequest.of(0, 20);
        given(spaceRepository.findAllByCreatorNamePattern("%", pageable))
                .willReturn(new PageImpl<>(List.of(otherSpace, space), pageable, 2));
        given(creatorRepository.findByCreatorIdIn(List.of(43L, 42L))).willReturn(List.of(creator, other));

        Page<CreatorSpaceView> result = service.findPublicSpaces(null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(view -> view.space().getCreatorId()).containsExactly(43L, 42L);
        assertThat(result.getContent()).extracting(CreatorSpaceView::creatorName)
                .containsExactly("다른크리에이터", "크리에이터");
    }

    @Test
    void findPublicSpacesReturnsEmptyPageWhenNothingMatches() {
        PageRequest pageable = PageRequest.of(0, 20);
        given(spaceRepository.findAllByCreatorNamePattern("%없음%", pageable))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));
        given(creatorRepository.findByCreatorIdIn(List.of())).willReturn(List.of());

        Page<CreatorSpaceView> result = service.findPublicSpaces("없음", pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void findPublicSpacesTreatsBlankKeywordAsAll() {
        PageRequest pageable = PageRequest.of(0, 20);
        given(spaceRepository.findAllByCreatorNamePattern("%", pageable))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        service.findPublicSpaces("   ", pageable);

        then(spaceRepository).should().findAllByCreatorNamePattern("%", pageable);
    }

    /** 검색어의 LIKE 특수문자는 문자 그대로 찾도록 이스케이프하고, 앞뒤 공백은 제거한다. */
    @Test
    void findPublicSpacesEscapesLikeWildcardsInKeyword() {
        PageRequest pageable = PageRequest.of(0, 20);
        given(spaceRepository.findAllByCreatorNamePattern("%100!%!_a!!%", pageable))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        service.findPublicSpaces(" 100%_a! ", pageable);

        then(spaceRepository).should().findAllByCreatorNamePattern("%100!%!_a!!%", pageable);
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

    /** Space가 없는 Creator가 처음 저장하면 템플릿으로 만든 뒤 보낸 값이 그대로 적용된다. */
    @Test
    void updateMineCreatesTheMissingSpaceAndAppliesTheRequestedValues() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(creatorRepository.findByIdForUpdate(42L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.empty());
        given(spaceService.createFromActiveTemplateIfAbsent(42L)).willReturn(space);

        service.updateMine(7L, NEW_PROFILE);

        var lockOrder = inOrder(creatorRepository, spaceRepository, spaceService);
        lockOrder.verify(creatorRepository).findByIdForUpdate(42L);
        lockOrder.verify(spaceRepository).findByCreatorIdForUpdate(42L);
        lockOrder.verify(spaceService).createFromActiveTemplateIfAbsent(42L);
        assertThat(space.getIntroText()).isEqualTo(NEW_PROFILE.introText());
        assertThat(space.getBannerImageUrl()).isEqualTo(NEW_PROFILE.bannerImageUrl());
    }

    /** Creator 행을 잠그기 전에 다른 요청이 Space를 만든 경우, 잠금 이후 현재 Space를 재조회해 재사용한다. */
    @Test
    void updateMineUsesTheSpaceCommittedBeforeCreatorLock() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));

        service.updateMine(7L, NEW_PROFILE);

        var lockOrder = inOrder(creatorRepository, spaceRepository);
        lockOrder.verify(creatorRepository).findByIdForUpdate(42L);
        lockOrder.verify(spaceRepository).findByCreatorIdForUpdate(42L);
        then(spaceService).should(never()).createFromActiveTemplateIfAbsent(any());
        assertThat(space.getIntroText()).isEqualTo(NEW_PROFILE.introText());
    }

    /** 첫 저장으로 만든 Space에 회원 사진이 복사돼 있어도, 요청이 다른 프로필 이미지 URL을 보냈다면 요청 값이 우선이다. */
    @Test
    void updateMineReleasesTheCarriedMemberImageWhenTheFirstRequestSendsAnotherUrl() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(creatorRepository.findByIdForUpdate(42L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.empty());
        space.updateProfileImage("creator-space-profile-images/42/copy.jpg", "https://cdn/copy.jpg");
        given(spaceService.createFromActiveTemplateIfAbsent(42L)).willReturn(space);

        service.updateMine(7L, new CreatorSpaceProfileFields("소개", "https://img/mine.png", "https://img/banner.png"));

        then(profileImageManager).should().apply(creator, space, null, true);
    }

    /** 현재 URL을 그대로 보낸 첫 저장은 복사해 둔 회원 사진을 건드리지 않는다. */
    @Test
    void updateMineKeepsTheCarriedMemberImageWhenTheFirstRequestSendsTheCurrentUrl() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(creatorRepository.findByIdForUpdate(42L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.empty());
        space.updateProfileImage("creator-space-profile-images/42/copy.jpg", "https://cdn/copy.jpg");
        given(spaceService.createFromActiveTemplateIfAbsent(42L)).willReturn(space);

        service.updateMine(7L, new CreatorSpaceProfileFields("소개", "https://cdn/copy.jpg", "https://img/banner.png"));

        then(profileImageManager).should(never()).apply(creator, space, null, true);
    }

    /** Space가 이미 있어도 락 순서를 유지하되, 새 Space를 만들지 않는다. */
    @Test
    void updateMineDoesNotCreateASpaceWhenOneExists() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));

        service.updateMine(7L, NEW_PROFILE);

        then(spaceService).should(never()).createFromActiveTemplateIfAbsent(any());
        then(creatorRepository).should().findByIdForUpdate(42L);
    }

    @Test
    void updateMineChangesOwnSpaceProfile() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));

        CreatorSpaceView view = service.updateMine(7L, NEW_PROFILE);

        assertThat(view.space().getIntroText()).isEqualTo("새 소개");
        assertThat(view.space().getSlug()).isEqualTo("creator-42");
    }

    /** 새로 적용되는 URL만 고정한다. 템플릿 이미지를 이 URL로 기록하는 경로이므로 삭제 확정과 겹치지 않아야 한다(이슈 #598). */
    @Test
    void updateMinePinsOnlyTheUrlsThatActuallyChange() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));

        service.updateMine(7L, new CreatorSpaceProfileFields("새 소개", "https://img/profile.png", "https://img/b2.png"));

        then(imagePinner).should().pin(null, "https://img/b2.png");
    }

    /** FE는 저장할 때 현재 URL을 그대로 다시 보낸다. 고정하지 않아야 정리된 이미지를 가진 Space도 소개글을 고칠 수 있다. */
    @Test
    void updateMineDoesNotPinUrlsThatAreUnchanged() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));

        service.updateMine(7L, new CreatorSpaceProfileFields("새 소개", "https://img/profile.png", "https://img/banner.png"));

        then(imagePinner).should().pin(null, null);
        assertThat(space.getIntroText()).isEqualTo("새 소개");
    }

    @Test
    void updateMineDoesNotPinUrlsIgnoredBecauseAnUploadedImageIsLinked() {
        space.updateProfileImage("creator-space-profile-images/42/current.jpg", "https://cdn/current.jpg");
        space.updateBannerImage("creator-space-profile-images/42/banner/current.jpg", "https://cdn/banner.jpg");
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));

        service.updateMine(7L, NEW_PROFILE);

        then(imagePinner).should().pin(null, null);
    }

    @Test
    void updateMineIsRejectedAndLeavesTheSpaceUnchangedWhenAPinnedImageWasCleanedUp() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));
        willThrow(new BusinessException(CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE)).given(imagePinner).pin(any(), any());

        assertErrorCode(() -> service.updateMine(7L, NEW_PROFILE), CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE);

        assertThat(space.getIntroText()).isEqualTo("소개");
        assertThat(space.getProfileImageUrl()).isEqualTo("https://img/profile.png");
        then(profileImageManager).shouldHaveNoInteractions();
    }

    @Test
    void updateMineDelegatesConnectedImageUpdateToImageManager() {
        space.updateProfileImage("creator-space-profile-images/42/current.jpg", "https://cdn/current.jpg");
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));

        service.updateMine(7L, NEW_PROFILE);

        then(profileImageManager).should().apply(creator, space, null, false);
        assertThat(space.getProfileImageUrl()).isEqualTo("https://cdn/current.jpg");
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/b2.png");
    }

    @Test
    void updateMineKeepsDefaultImageWhenExplicitNullImageKeyRestoresIt() {
        space.updateProfileImage("creator-space-profile-images/42/current.jpg", "https://cdn/current.jpg");
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));
        CreatorSpaceProfileImageRepository imageRepository = mock(CreatorSpaceProfileImageRepository.class);
        CreatorSpaceProfileImageManager realImageManager = new CreatorSpaceProfileImageManager(
                imageRepository,
                new CreatorSpaceProfileImageUrlResolver(new CreatorSpaceProfileImageProperties("https://cdn.example")),
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        CreatorSpaceProfileService serviceWithRealImageManager = new CreatorSpaceProfileService(
                creatorRepository, spaceRepository, new CreatorSpaceSlugRegistry(spaceRepository, reservationRepository),
                realImageManager, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), followQueryService, mock(EventQueryService.class), imagePinner, contentOutbox, spaceService);

        serviceWithRealImageManager.updateMine(7L, new CreatorSpaceProfileFields(
                "새 소개", "https://cdn/current.jpg", "https://img/b2.png", null, true));

        assertThat(space.getProfileImageKey()).isNull();
        assertThat(space.getProfileImageUrl()).isEqualTo("https://img/profile.png");
        then(imageRepository).should().markDeletePending(
                "creator-space-profile-images/42/current.jpg", 42L, NOW.toInstant(ZoneOffset.UTC));
    }

    @Test
    void updateMinePassesBannerImageKeyToImageManager() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));

        service.updateMine(7L, new CreatorSpaceProfileFields(
                "새 소개", "https://img/p2.png", "https://img/b2.png", null, false,
                "creator-space-profile-images/42/banner/new.jpg", true));

        then(profileImageManager).should().apply(creator, space, null, false);
        then(profileImageManager).should().applyBanner(creator, space, "creator-space-profile-images/42/banner/new.jpg", true);
    }

    @Test
    void updateMineKeepsDefaultBannerWhenExplicitNullBannerKeyRestoresIt() {
        space.updateBannerImage("creator-space-profile-images/42/banner/current.jpg", "https://cdn/current.jpg");
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));
        CreatorSpaceProfileImageRepository imageRepository = mock(CreatorSpaceProfileImageRepository.class);
        CreatorSpaceProfileImageManager realImageManager = new CreatorSpaceProfileImageManager(
                imageRepository,
                new CreatorSpaceProfileImageUrlResolver(new CreatorSpaceProfileImageProperties("https://cdn.example")),
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        CreatorSpaceProfileService serviceWithRealImageManager = new CreatorSpaceProfileService(
                creatorRepository, spaceRepository, new CreatorSpaceSlugRegistry(spaceRepository, reservationRepository),
                realImageManager, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), followQueryService, mock(EventQueryService.class), imagePinner, contentOutbox, spaceService);

        // 클라이언트가 현재 배너 URL(업로드 이미지)을 그대로 보내도 기본 배너로 저장되지 않아야 한다.
        serviceWithRealImageManager.updateMine(7L, new CreatorSpaceProfileFields(
                "새 소개", "https://img/p2.png", "https://cdn/current.jpg", null, false, null, true));

        assertThat(space.getBannerImageKey()).isNull();
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/banner.png");
        then(imageRepository).should().markDeletePending(
                "creator-space-profile-images/42/banner/current.jpg", 42L, NOW.toInstant(ZoneOffset.UTC));
    }

    @Test
    void updateMineThrowsForbiddenWhenCallerIsNotCreator() {
        given(creatorRepository.findByMemberId(8L)).willReturn(Optional.empty());

        assertErrorCode(() -> service.updateMine(8L, NEW_PROFILE), CommonErrorCode.FORBIDDEN);
    }

    @Test
    void changeSlugUpdatesToAvailableCustomSlug() {
        givenOwnSpace();
        given(spaceRepository.existsBySlug("iu-official")).willReturn(false);

        CreatorSpaceView view = service.changeSlug(7L, "iu-official");

        assertThat(view.space().getSlug()).isEqualTo("iu-official");
        assertThat(view.space().getSlugChangedAt()).isEqualTo(NOW);
        assertThat(view.space().slugChangeableAt()).isEqualTo(NOW.plusDays(14));
        then(spaceRepository).should().saveAndFlush(space);
    }

    /** 마지막 변경 후 14일이 지나지 않았으면 다시 바꿀 수 없다. */
    @Test
    void changeSlugRejectsChangeWithinFourteenDays() {
        space.changeSlug("iu-official", NOW.minusDays(14).plusSeconds(1));
        givenOwnSpace();

        assertErrorCode(() -> service.changeSlug(7L, "iu-2026"), CreatorErrorCode.SLUG_CHANGE_TOO_SOON);
        assertThat(space.getSlug()).isEqualTo("iu-official");
    }

    @Test
    void changeSlugAllowsChangeExactlyFourteenDaysLater() {
        space.changeSlug("iu-official", NOW.minusDays(14));
        givenOwnSpace();
        given(spaceRepository.existsBySlug("iu-2026")).willReturn(false);

        assertThat(service.changeSlug(7L, "iu-2026").space().getSlug()).isEqualTo("iu-2026");
    }

    /** 지금 slug와 같으면 중복 검사 없이 그대로 성공한다. */
    @Test
    void changeSlugToCurrentSlugIsNoOp() {
        givenOwnSpace();

        CreatorSpaceView view = service.changeSlug(7L, "creator-42");

        assertThat(view.space().getSlug()).isEqualTo("creator-42");
        then(spaceRepository).should(never()).existsBySlug(any());
        then(spaceRepository).should(never()).saveAndFlush(any());
    }

    /** Controller 검증을 거치지 않은 호출도 형식을 다시 검사한다. */
    @Test
    void changeSlugRejectsInvalidFormat() {
        givenOwnSpace();

        assertErrorCode(() -> service.changeSlug(7L, "IU"), CommonErrorCode.VALIDATION_FAILED);
        assertThat(space.getSlug()).isEqualTo("creator-42");
    }

    @Test
    void changeSlugRejectsReservedWord() {
        givenOwnSpace();

        assertErrorCode(() -> service.changeSlug(7L, "admin"), CreatorErrorCode.RESERVED_SLUG);
    }

    @Test
    void changeSlugRejectsSlugUsedByAnotherSpace() {
        givenOwnSpace();
        given(spaceRepository.existsBySlug("taken")).willReturn(true);

        assertErrorCode(() -> service.changeSlug(7L, "taken"), CreatorErrorCode.SLUG_ALREADY_TAKEN);
        assertThat(space.getSlug()).isEqualTo("creator-42");
    }

    /** 조회 후 저장 사이에 다른 Creator가 같은 slug를 가져가면 DB UNIQUE 위반을 409로 바꾼다. */
    @Test
    void changeSlugMapsConcurrentUniqueViolationToTaken() {
        givenOwnSpace();
        given(spaceRepository.existsBySlug("race")).willReturn(false);
        given(spaceRepository.saveAndFlush(space)).willThrow(new DataIntegrityViolationException("uk_creator_space_slug"));

        assertErrorCode(() -> service.changeSlug(7L, "race"), CreatorErrorCode.SLUG_ALREADY_TAKEN);
    }

    @Test
    void changeSlugThrowsForbiddenWhenCallerIsNotCreator() {
        given(creatorRepository.findByMemberId(8L)).willReturn(Optional.empty());

        assertErrorCode(() -> service.changeSlug(8L, "iu-official"), CommonErrorCode.FORBIDDEN);
    }

    /** 버린 이전 slug는 14일 동안 이 Creator 이름으로 예약된다(이슈 #301). */
    @Test
    void changeSlugReservesReleasedSlugForFourteenDays() {
        givenOwnSpace();

        service.changeSlug(7L, "iu-official");

        ArgumentCaptor<CreatorSpaceSlugReservation> captor = ArgumentCaptor.forClass(CreatorSpaceSlugReservation.class);
        then(reservationRepository).should().save(captor.capture());
        assertThat(captor.getValue().getSlug()).isEqualTo("creator-42");
        assertThat(captor.getValue().getCreatorId()).isEqualTo(42L);
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(NOW.plusDays(14));
    }

    @Test
    void changeSlugRejectsSlugReservedByAnotherCreator() {
        givenOwnSpace();
        given(reservationRepository.findBySlug("iu-official"))
                .willReturn(Optional.of(CreatorSpaceSlugReservation.reserve("iu-official", 99L, NOW.minusDays(13))));

        assertErrorCode(() -> service.changeSlug(7L, "iu-official"), CreatorErrorCode.SLUG_ALREADY_TAKEN);
        assertThat(space.getSlug()).isEqualTo("creator-42");
    }

    /** 예약 기간이 끝난 slug는 누구나 쓸 수 있고, 남은 예약 행은 지운다. */
    @Test
    void changeSlugAllowsSlugWhoseReservationExpired() {
        givenOwnSpace();
        CreatorSpaceSlugReservation expired = CreatorSpaceSlugReservation.reserve("iu-official", 99L, NOW.minusDays(14));
        given(reservationRepository.findBySlug("iu-official")).willReturn(Optional.of(expired));

        assertThat(service.changeSlug(7L, "iu-official").space().getSlug()).isEqualTo("iu-official");
        then(reservationRepository).should().delete(expired);
    }

    /**
     * 본인이 버린 slug로는 14일 변경 제한 안에서도 되돌릴 수 있다. 변경 시각은 그대로라서,
     * 되돌린 뒤 새 slug로 바꾸는 제한은 원래 변경 시각 기준으로 계속된다.
     */
    @Test
    void changeSlugRevertsToOwnReservedSlugWithinChangeInterval() {
        space.changeSlug("iu-official", NOW.minusDays(3));
        givenOwnSpace();
        given(reservationRepository.findBySlug("creator-42"))
                .willReturn(Optional.of(CreatorSpaceSlugReservation.reserve("creator-42", 42L, NOW.minusDays(3))));

        CreatorSpaceView view = service.changeSlug(7L, "creator-42");

        assertThat(view.space().getSlug()).isEqualTo("creator-42");
        assertThat(view.space().getSlugChangedAt()).isEqualTo(NOW.minusDays(3));
        ArgumentCaptor<CreatorSpaceSlugReservation> captor = ArgumentCaptor.forClass(CreatorSpaceSlugReservation.class);
        then(reservationRepository).should().save(captor.capture());
        assertThat(captor.getValue().getSlug()).isEqualTo("iu-official");
        assertErrorCode(() -> service.changeSlug(7L, "iu-2026"), CreatorErrorCode.SLUG_CHANGE_TOO_SOON);
    }

    /** 예약어가 나중에 추가돼 예전에 버린 slug가 예약어가 됐다면 되돌릴 수 없다. */
    @Test
    void changeSlugRejectsRevertToSlugThatBecameReserved() {
        space.changeSlug("iu-official", NOW.minusDays(3));
        givenOwnSpace();
        given(reservationRepository.findBySlug("explore"))
                .willReturn(Optional.of(CreatorSpaceSlugReservation.reserve("explore", 42L, NOW.minusDays(3))));

        assertErrorCode(() -> service.changeSlug(7L, "explore"), CreatorErrorCode.RESERVED_SLUG);
    }

    /** 긴 템플릿 규칙으로 만든 30자 초과 자동 slug도 본인이 버린 것이면 되돌릴 수 있다. */
    @Test
    void changeSlugRevertsToOwnLongAutoSlug() {
        String longAutoSlug = "creator-official-fan-space-of-" + 42;
        CreatorSpace longSpace = CreatorSpace.fromTemplate(42L, new CreatorSpaceTemplate(
                1L, "소개", "p", "b", "creator-official-fan-space-of-{creatorId}"
        ), longAutoSlug);
        longSpace.changeSlug("iu-official", NOW.minusDays(1));
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(longSpace));
        given(reservationRepository.findBySlug(longAutoSlug))
                .willReturn(Optional.of(CreatorSpaceSlugReservation.reserve(longAutoSlug, 42L, NOW.minusDays(1))));

        assertThat(longAutoSlug.length()).isGreaterThan(30);
        assertThat(service.changeSlug(7L, longAutoSlug).space().getSlug()).isEqualTo(longAutoSlug);
    }

    private void givenOwnSpace() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));
    }

    private void assertErrorCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
