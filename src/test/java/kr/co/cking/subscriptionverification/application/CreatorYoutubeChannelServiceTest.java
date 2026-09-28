package kr.co.cking.subscriptionverification.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.YoutubeSubscriptionMissionProvisioningService;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.repository.CreatorYoutubeChannelRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

class CreatorYoutubeChannelServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long CREATOR_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-09-28T03:00:00Z");

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorYoutubeChannelRepository channelRepository = mock(CreatorYoutubeChannelRepository.class);
    private final SubscriptionVerificationRepository verificationRepository =
            mock(SubscriptionVerificationRepository.class);
    private final YoutubeSubscriptionMissionProvisioningService missionProvisioningService =
            mock(YoutubeSubscriptionMissionProvisioningService.class);
    private final CreatorYoutubeChannelService service = new CreatorYoutubeChannelService(
            memberRepository, creatorRepository, channelRepository, verificationRepository,
            missionProvisioningService, Clock.fixed(NOW, ZoneOffset.UTC));

    private Creator creator;

    @BeforeEach
    void setUp() {
        creator = new Creator(MEMBER_ID, "Creator");
        ReflectionTestUtils.setField(creator, "creatorId", CREATOR_ID);
        given(memberRepository.findById(MEMBER_ID))
                .willReturn(Optional.of(new Member("회원", null, null, MemberRole.USER)));
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator));
        given(creatorRepository.findByIdForUpdate(CREATOR_ID)).willReturn(Optional.of(creator));
    }

    @Test
    void 최초_설정은_채널과_구독_미션을_생성한다() {
        given(channelRepository.findByCreatorIdForUpdate(CREATOR_ID)).willReturn(Optional.empty());
        given(channelRepository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

        CreatorYoutubeChannelUpsertResult result = service.put(
                MEMBER_ID, new CreatorYoutubeChannelCommand("예상치 못한 필름", "@UnexpectedFilm"));

        assertThat(result.created()).isTrue();
        assertThat(result.channel().getChannelHandle()).isEqualTo("@unexpectedfilm");
        then(missionProvisioningService).should().provision(CREATOR_ID);
    }

    @Test
    void 정규화_결과가_같으면_활성_인증이_있어도_기존_설정을_반환한다() {
        CreatorYoutubeChannel existing = channel("@unexpectedfilm");
        given(channelRepository.findByCreatorIdForUpdate(CREATOR_ID)).willReturn(Optional.of(existing));

        CreatorYoutubeChannelUpsertResult result = service.put(
                MEMBER_ID, new CreatorYoutubeChannelCommand(" 예상치 못한 필름 ", "@@UnexpectedFilm"));

        assertThat(result.created()).isFalse();
        assertThat(result.channel()).isSameAs(existing);
        then(verificationRepository).should(never()).existsByCreatorIdAndStatusIn(any(), any());
        then(channelRepository).should(never()).saveAndFlush(any());
        then(missionProvisioningService).should(never()).provision(any());
    }

    @Test
    void 활성_인증이_있으면_다른_채널로_수정할_수_없다() {
        given(channelRepository.findByCreatorIdForUpdate(CREATOR_ID))
                .willReturn(Optional.of(channel("@old")));
        given(verificationRepository.existsByCreatorIdAndStatusIn(eq(CREATOR_ID), any()))
                .willReturn(true);

        assertError(
                () -> service.put(MEMBER_ID, new CreatorYoutubeChannelCommand("새 채널", "@new")),
                SubscriptionVerificationErrorCode.INVALID_STATE);
        then(channelRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void APPROVED만_있으면_채널을_수정할_수_있다() {
        CreatorYoutubeChannel existing = channel("@old");
        given(channelRepository.findByCreatorIdForUpdate(CREATOR_ID)).willReturn(Optional.of(existing));
        given(verificationRepository.existsByCreatorIdAndStatusIn(eq(CREATOR_ID), any()))
                .willReturn(false);
        given(channelRepository.saveAndFlush(existing)).willReturn(existing);

        CreatorYoutubeChannelUpsertResult result = service.put(
                MEMBER_ID, new CreatorYoutubeChannelCommand("새 채널", "@new"));

        assertThat(result.created()).isFalse();
        assertThat(result.channel().getChannelHandle()).isEqualTo("@new");
        then(missionProvisioningService).should(never()).provision(any());
    }

    @Test
    void 다른_Creator의_handle은_사용할_수_없다() {
        given(channelRepository.findByCreatorIdForUpdate(CREATOR_ID)).willReturn(Optional.empty());
        given(channelRepository.existsByChannelHandleAndCreatorIdNot("@taken", CREATOR_ID)).willReturn(true);

        assertError(
                () -> service.put(MEMBER_ID, new CreatorYoutubeChannelCommand("채널", "@TAKEN")),
                SubscriptionVerificationErrorCode.CHANNEL_HANDLE_CONFLICT);
    }

    @Test
    void DB_UNIQUE_경쟁도_handle_충돌로_변환한다() {
        given(channelRepository.findByCreatorIdForUpdate(CREATOR_ID)).willReturn(Optional.empty());
        given(channelRepository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("uk_creator_youtube_channel_handle"));

        assertError(
                () -> service.put(MEMBER_ID, new CreatorYoutubeChannelCommand("채널", "@race")),
                SubscriptionVerificationErrorCode.CHANNEL_HANDLE_CONFLICT);
    }

    @Test
    void Member가_없으면_RESOURCE_NOT_FOUND_Creator가_아니면_FORBIDDEN이다() {
        given(memberRepository.findById(99L)).willReturn(Optional.empty());
        assertError(
                () -> service.put(99L, new CreatorYoutubeChannelCommand("채널", "@sample")),
                CommonErrorCode.RESOURCE_NOT_FOUND);

        given(memberRepository.findById(98L))
                .willReturn(Optional.of(new Member("회원", null, null, MemberRole.USER)));
        given(creatorRepository.findByMemberId(98L)).willReturn(Optional.empty());
        assertError(
                () -> service.put(98L, new CreatorYoutubeChannelCommand("채널", "@sample")),
                CommonErrorCode.FORBIDDEN);
    }

    @Test
    void 공개_조회는_Creator와_채널_존재를_검증한다() {
        CreatorYoutubeChannel channel = channel("@sample");
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);
        given(channelRepository.findById(CREATOR_ID)).willReturn(Optional.of(channel));

        assertThat(service.getPublic(CREATOR_ID)).isSameAs(channel);

        given(creatorRepository.existsById(100L)).willReturn(false);
        assertError(() -> service.getPublic(100L), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 유효하지_않은_handle은_VALIDATION_FAILED다() {
        assertError(
                () -> service.put(MEMBER_ID, new CreatorYoutubeChannelCommand("채널", "bad handle")),
                CommonErrorCode.VALIDATION_FAILED);
    }

    private CreatorYoutubeChannel channel(String handle) {
        return new CreatorYoutubeChannel(CREATOR_ID, "예상치 못한 필름", handle, NOW.minusSeconds(60));
    }

    private void assertError(Runnable action, kr.co.cking.common.exception.ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }
}
