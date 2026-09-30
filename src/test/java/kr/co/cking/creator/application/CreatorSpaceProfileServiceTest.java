package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceSlugReservation;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.creator.repository.CreatorSpaceSlugReservationRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

class CreatorSpaceProfileServiceTest {

    private static final CreatorSpaceProfileFields NEW_PROFILE = new CreatorSpaceProfileFields(
            "새 소개", "https://img/p2.png", "https://img/b2.png"
    );

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 3, 0);

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
    private final CreatorSpaceSlugReservationRepository reservationRepository = mock(CreatorSpaceSlugReservationRepository.class);
    private final CreatorSpaceProfileService service = new CreatorSpaceProfileService(
            creatorRepository, spaceRepository, new CreatorSpaceSlugRegistry(spaceRepository, reservationRepository),
            Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    private Creator creator;
    private CreatorSpace space;

    @BeforeEach
    void setUp() {
        creator = new Creator(7L, "크리에이터");
        ReflectionTestUtils.setField(creator, "creatorId", 42L);
        space = CreatorSpace.fromTemplate(42L, new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"
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
    void findPublicSpacesAttachesCreatorNamesAndKeepsRepositoryOrder() {
        Creator other = new Creator(8L, "다른 크리에이터");
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
                .containsExactly("다른 크리에이터", "크리에이터");
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

    @Test
    void updateMineChangesOwnSpaceProfile() {
        given(creatorRepository.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaceRepository.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));

        CreatorSpaceView view = service.updateMine(7L, NEW_PROFILE);

        assertThat(view.space().getIntroText()).isEqualTo("새 소개");
        assertThat(view.space().getSlug()).isEqualTo("creator-42");
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
