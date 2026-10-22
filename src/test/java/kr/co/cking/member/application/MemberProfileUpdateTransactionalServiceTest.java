package kr.co.cking.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberErrorCode;
import kr.co.cking.member.domain.MemberNickname;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberProfileImageRepository;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class MemberProfileUpdateTransactionalServiceTest {

    private static final Long MEMBER_ID = 10L;
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MemberProfileImageRepository imageRepository = mock(MemberProfileImageRepository.class);
    private final MemberNicknameTransactionalService nicknameService = mock(MemberNicknameTransactionalService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final MemberProfileUpdateTransactionalService service = new MemberProfileUpdateTransactionalService(
            memberRepository, imageRepository, nicknameService, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 본인이_업로드한_새_사진을_연결하고_이전_사진은_삭제_대기로_바꾼다() {
        Member member = memberWithCurrentImage("member-profile-images/10/old.jpg");
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
        given(imageRepository.linkToProfile("member-profile-images/10/new.jpg", MEMBER_ID, NOW)).willReturn(1);
        given(imageRepository.markDeletePending("member-profile-images/10/old.jpg", MEMBER_ID, NOW)).willReturn(1);

        service.update(MEMBER_ID,
                new MemberProfileUpdateService.UpdateFields("fan", "member-profile-images/10/new.jpg", true));

        verify(nicknameService).change(member, "fan");
        verify(imageRepository).linkToProfile("member-profile-images/10/new.jpg", MEMBER_ID, NOW);
        verify(imageRepository).markDeletePending("member-profile-images/10/old.jpg", MEMBER_ID, NOW);
        verify(eventPublisher).publishEvent(new MemberProfileImageDeletionRequestedEvent("member-profile-images/10/old.jpg"));
        verify(memberRepository).saveAndFlush(member);
        assertThat(member.getProfileImageKey()).isEqualTo("member-profile-images/10/new.jpg");
    }

    @Test
    void 다른_회원의_키는_연결하지_않고_거절한다() {
        Member member = memberWithCurrentImage(null);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
        given(imageRepository.linkToProfile("member-profile-images/99/other.jpg", MEMBER_ID, NOW)).willReturn(0);

        assertThatThrownBy(() -> service.update(MEMBER_ID,
                new MemberProfileUpdateService.UpdateFields("fan", "member-profile-images/99/other.jpg", true)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.PROFILE_IMAGE_UNAVAILABLE);

        verify(memberRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 같은_키는_다시_연결하거나_삭제하지_않는다() {
        Member member = memberWithCurrentImage("member-profile-images/10/current.jpg");
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));

        service.update(MEMBER_ID,
                new MemberProfileUpdateService.UpdateFields("fan", "member-profile-images/10/current.jpg", true));

        verify(imageRepository, never()).linkToProfile(any(), any(), any());
        verify(imageRepository, never()).markDeletePending(any(), any(), any());
        verify(memberRepository).saveAndFlush(member);
    }

    @Test
    void 사진_키를_생략하면_현재_사진을_유지한다() {
        Member member = memberWithCurrentImage("member-profile-images/10/current.jpg");
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));

        service.update(MEMBER_ID, new MemberProfileUpdateService.UpdateFields("fan", null, false));

        verify(imageRepository, never()).linkToProfile(any(), any(), any());
        verify(imageRepository, never()).markDeletePending(any(), any(), any());
        verify(memberRepository).saveAndFlush(member);
        assertThat(member.getProfileImageKey()).isEqualTo("member-profile-images/10/current.jpg");
    }

    @Test
    void 사진_키에_null을_명시하면_기본_이미지로_전환한다() {
        Member member = memberWithCurrentImage("member-profile-images/10/current.jpg");
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
        given(imageRepository.markDeletePending("member-profile-images/10/current.jpg", MEMBER_ID, NOW)).willReturn(1);

        service.update(MEMBER_ID, new MemberProfileUpdateService.UpdateFields("fan", null, true));

        verify(imageRepository).markDeletePending("member-profile-images/10/current.jpg", MEMBER_ID, NOW);
        verify(eventPublisher).publishEvent(new MemberProfileImageDeletionRequestedEvent("member-profile-images/10/current.jpg"));
        assertThat(member.getProfileImageKey()).isNull();
    }

    private Member memberWithCurrentImage(String imageKey) {
        Member member = new Member("OAuth", null, null, MemberRole.USER);
        ReflectionTestUtils.setField(member, "memberId", MEMBER_ID);
        member.setInitialNickname(MemberNickname.of("fan"));
        member.updateProfileImageKey(imageKey);
        return member;
    }
}
