package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceFixtures;
import kr.co.cking.creator.domain.CreatorSpaceSlugReservation;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.common.storage.ObjectNotFoundException;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.creator.repository.CreatorSpaceSlugReservationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CreatorSpaceServiceTest {

    /** 관리자가 준비할 것 없이 기본 소개 문구, 이미지 없음, creator-{creatorId}로 만든다(이슈 #754). */
    @Test
    void createsSpaceWithDefaultsWhenAbsent() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.empty());
        given(spaceRepository.save(any(CreatorSpace.class))).willAnswer(invocation -> invocation.getArgument(0));
        CreatorSpaceService service = newService(spaceRepository);

        CreatorSpace space = service.createIfAbsent(42L);

        ArgumentCaptor<CreatorSpace> captor = ArgumentCaptor.forClass(CreatorSpace.class);
        verify(spaceRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(space);
        assertThat(space.getCreatorId()).isEqualTo(42L);
        assertThat(space.getSlug()).isEqualTo("creator-42");
        assertThat(space.getIntroText()).isEqualTo(CreatorSpace.DEFAULT_INTRO_TEXT);
        assertThat(space.getProfileImageUrl()).isNull();
        assertThat(space.getBannerImageUrl()).isNull();
        verify(contentOutbox).enqueue(space);
    }

    /** 회원 사진 URL만 가리키면 회원이 사진을 바꿀 때 깨지므로, Space용 파일로 복사해 연결한다. 기본 이미지(복귀 대상)는 없다. */
    @Test
    void copiesTheMemberProfileImageIntoASpaceOwnedFile() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        givenCreatorWithMemberImage("members/7/photo.jpg");
        byte[] bytes = {1, 2, 3};
        given(objectStorage.get("members/7/photo.jpg")).willReturn(bytes);
        given(imageRepository.link(any(), any(), any())).willReturn(1);
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.empty());
        given(spaceRepository.save(any(CreatorSpace.class))).willAnswer(invocation -> invocation.getArgument(0));
        CreatorSpaceService service = newService(spaceRepository);

        CreatorSpace space = service.createIfAbsent(42L);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(objectStorage).put(key.capture(), org.mockito.ArgumentMatchers.eq(bytes), org.mockito.ArgumentMatchers.eq("image/jpeg"));
        assertThat(key.getValue()).startsWith("creator-space-profile-images/42/").endsWith(".jpg");
        assertThat(space.getProfileImageKey()).isEqualTo(key.getValue());
        assertThat(space.getProfileImageUrl()).isEqualTo("https://space-cdn.example/" + key.getValue());
        // 기본 이미지로 되돌리면 이미지 없음이 된다.
        space.restoreDefaultProfileImage();
        assertThat(space.getProfileImageUrl()).isNull();
    }

    /** 원본이 이미 사라졌다면 승인을 막지 않고 프로필 이미지 없이 만든다. */
    @Test
    void createsWithoutProfileImageWhenTheMemberImageCannotBeCopied() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        givenCreatorWithMemberImage("members/7/gone.jpg");
        given(objectStorage.get("members/7/gone.jpg")).willThrow(new ObjectNotFoundException("members/7/gone.jpg"));
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.empty());
        given(spaceRepository.save(any(CreatorSpace.class))).willAnswer(invocation -> invocation.getArgument(0));
        CreatorSpaceService service = newService(spaceRepository);

        CreatorSpace space = service.createIfAbsent(42L);

        assertThat(space.getProfileImageKey()).isNull();
        assertThat(space.getProfileImageUrl()).isNull();
        verify(imageRepository, never()).save(any());
        verify(objectStorage, never()).put(any(), any(), any());
    }

    /** 저장소 쓰기가 실패해도 승인은 진행된다. 올리다 만 기록은 연결되지 않은 업로드로 남아 정리 작업이 회수한다. */
    @Test
    void createsWithoutProfileImageWhenStoringTheCopyFails() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        givenCreatorWithMemberImage("members/7/photo.jpg");
        given(objectStorage.get("members/7/photo.jpg")).willReturn(new byte[]{1});
        willThrow(new IllegalStateException("storage down")).given(objectStorage).put(any(), any(), any());
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.empty());
        given(spaceRepository.save(any(CreatorSpace.class))).willAnswer(invocation -> invocation.getArgument(0));
        CreatorSpaceService service = newService(spaceRepository);

        CreatorSpace space = service.createIfAbsent(42L);

        assertThat(space.getProfileImageKey()).isNull();
        assertThat(space.getProfileImageUrl()).isNull();
        verify(imageRepository, never()).markUploaded(any(), any());
        verify(imageRepository, never()).link(any(), any(), any());
    }

    /** DB 오류를 삼키면 트랜잭션이 rollback-only인 채 이어져 승인 커밋이 실패하므로, 그대로 전파해 승인 전체를 되돌린다. */
    @Test
    void propagatesDatabaseFailuresWhileRecordingTheCopy() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        givenCreatorWithMemberImage("members/7/photo.jpg");
        given(objectStorage.get("members/7/photo.jpg")).willReturn(new byte[]{1});
        willThrow(new IllegalStateException("db down")).given(imageRepository).save(any());
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.empty());
        given(spaceRepository.save(any(CreatorSpace.class))).willAnswer(invocation -> invocation.getArgument(0));
        CreatorSpaceService service = newService(spaceRepository);

        assertThatThrownBy(() -> service.createIfAbsent(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
        verify(objectStorage, never()).put(any(), any(), any());
    }

    @Test
    void createsWithoutProfileImageWhenTheMemberHasNoProfileImage() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        givenCreatorWithMemberImage(null);
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.empty());
        given(spaceRepository.save(any(CreatorSpace.class))).willAnswer(invocation -> invocation.getArgument(0));
        CreatorSpaceService service = newService(spaceRepository);

        CreatorSpace space = service.createIfAbsent(42L);

        assertThat(space.getProfileImageUrl()).isNull();
        verify(objectStorage, never()).get(any());
    }

    private void givenCreatorWithMemberImage(String imageKey) {
        Creator creator = new Creator(7L, "크리에이터");
        Member member = mock(Member.class);
        given(member.getProfileImageKey()).willReturn(imageKey);
        given(creatorRepository.findById(42L)).willReturn(Optional.of(creator));
        given(memberRepository.findById(7L)).willReturn(Optional.of(member));
    }

    /** 다른 Creator가 커스텀 slug로 자동 slug를 먼저 가져갔으면 번호를 붙인 대체 slug를 써서 승인을 이어간다. */
    @Test
    void usesNumberedSlugWhenAutoSlugIsTakenByCustomSlug() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        given(spaceRepository.findByCreatorId(43L)).willReturn(Optional.empty());
        given(spaceRepository.existsBySlug("creator-43")).willReturn(true);
        given(spaceRepository.existsBySlug("creator-43-2")).willReturn(true);
        given(spaceRepository.existsBySlug("creator-43-3")).willReturn(false);
        given(spaceRepository.save(any(CreatorSpace.class))).willAnswer(invocation -> invocation.getArgument(0));
        CreatorSpaceService service = newService(spaceRepository);

        CreatorSpace space = service.createIfAbsent(43L);

        assertThat(space.getSlug()).isEqualTo("creator-43-3");
    }

    /** 다른 Creator가 버려서 예약 중인 slug도 사용 중으로 보고 대체 slug를 쓴다(이슈 #301). */
    @Test
    void usesNumberedSlugWhenAutoSlugIsReservedByAnotherCreator() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        given(spaceRepository.findByCreatorId(43L)).willReturn(Optional.empty());
        given(reservationRepository.findBySlug("creator-43"))
                .willReturn(Optional.of(CreatorSpaceSlugReservation.reserve("creator-43", 42L, NOW.minusDays(1))));
        given(spaceRepository.save(any(CreatorSpace.class))).willAnswer(invocation -> invocation.getArgument(0));
        CreatorSpaceService service = newService(spaceRepository);

        CreatorSpace space = service.createIfAbsent(43L);

        assertThat(space.getSlug()).isEqualTo("creator-43-2");
    }

    /** 이미 Space가 있으면 새로 저장하지 않는다 — 승인 재시도·중복 호출에도 Space가 두 개 생기지 않게 한다. */
    @Test
    void returnsExistingSpaceWithoutCreatingDuplicate() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        CreatorSpace existing = CreatorSpaceFixtures.space(42L, "소개", "p", "b", "creator-42");
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.of(existing));
        CreatorSpaceService service = newService(spaceRepository);

        CreatorSpace result = service.createIfAbsent(42L);

        assertThat(result).isSameAs(existing);
        verify(spaceRepository, never()).save(any());
        verifyNoInteractions(contentOutbox);
    }

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 3, 0);

    private final CreatorSpaceSlugReservationRepository reservationRepository = mock(CreatorSpaceSlugReservationRepository.class);
    private final CreatorContentOutbox contentOutbox = mock(CreatorContentOutbox.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final ObjectStorage objectStorage = mock(ObjectStorage.class);
    private final CreatorSpaceProfileImageRepository imageRepository = mock(CreatorSpaceProfileImageRepository.class);

    private CreatorSpaceService newService(CreatorSpaceRepository spaceRepository) {
        return new CreatorSpaceService(
                spaceRepository,
                new CreatorSpaceSlugRegistry(spaceRepository, reservationRepository),
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), contentOutbox,
                creatorRepository, memberRepository,
                objectStorage, imageRepository,
                new CreatorSpaceProfileImageUrlResolver(new CreatorSpaceProfileImageProperties("https://space-cdn.example")));
    }
}
