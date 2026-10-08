package kr.co.cking.follow.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.domain.FollowErrorCode;
import kr.co.cking.follow.repository.CreatorFollowRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.member.domain.Member;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import kr.co.cking.common.repository.DatabaseTime;
import kr.co.cking.common.repository.MemberActivityLock;
import kr.co.cking.common.event.CreatorFollowCreated;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CreatorFollowServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private static final Long MEMBER_ID = 10L;
    private static final Long CREATOR_ID = 1L;
    private static final Long CREATOR_OWNER_MEMBER_ID = 99L;

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorFollowRepository followRepository = mock(CreatorFollowRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final DatabaseTime databaseTime = mock(DatabaseTime.class);
    private final MemberActivityLock activityLock = mock(MemberActivityLock.class);
    private final CreatorFollowEventService followEvents = mock(CreatorFollowEventService.class);
    private final CreatorFollowService service = new CreatorFollowService(
            creatorRepository, followRepository, databaseTime, memberRepository, eventPublisher, activityLock, followEvents);

    @BeforeEach
    void setUp() {
        given(memberRepository.existsById(anyLong())).willReturn(true);
        given(databaseTime.now()).willReturn(NOW);
    }

    @Test
    void 팔로우는_관계가_없을_때만_추가하는_insert를_호출한다() {
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator()));

        service.follow(MEMBER_ID, CREATOR_ID);

        verify(followRepository).insertIfAbsent(MEMBER_ID, CREATOR_ID, NOW);
        var event = ArgumentCaptor.forClass(CreatorFollowCreated.class);
        verify(followEvents).append(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().memberId()).isEqualTo(MEMBER_ID);
        org.assertj.core.api.Assertions.assertThat(event.getValue().creatorId()).isEqualTo(CREATOR_ID);
        org.assertj.core.api.Assertions.assertThat(event.getValue().followedAt()).isEqualTo(NOW);
        verify(eventPublisher).publishEvent(event.getValue());
    }

    @Test
    void 반복_팔로우는_신규_전환_이벤트를_발행하지_않는다() {
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator()));
        given(followRepository.existsByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)).willReturn(true);
        service.follow(MEMBER_ID, CREATOR_ID);
        verify(eventPublisher, never()).publishEvent(any());
        verify(followRepository, never()).insertIfAbsent(anyLong(), anyLong(), any());
    }

    @Test
    void 존재하지_않는_크리에이터는_팔로우할_수_없다() {
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.follow(MEMBER_ID, CREATOR_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(followRepository, never()).insertIfAbsent(anyLong(), anyLong(), any());
    }

    @Test
    void 본인_크리에이터는_팔로우할_수_없다() {
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator()));

        assertThatThrownBy(() -> service.follow(CREATOR_OWNER_MEMBER_ID, CREATOR_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(FollowErrorCode.SELF_FOLLOW_NOT_ALLOWED));
        verify(followRepository, never()).insertIfAbsent(anyLong(), anyLong(), any());
    }

    @Test
    void 언팔로우는_관계를_삭제한다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);

        service.unfollow(MEMBER_ID, CREATOR_ID);

        verify(followRepository).deleteByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID);
    }

    @Test
    void 존재하지_않는_크리에이터는_언팔로우할_수_없다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(false);

        assertThatThrownBy(() -> service.unfollow(MEMBER_ID, CREATOR_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(followRepository, never()).deleteByMemberIdAndCreatorId(anyLong(), anyLong());
    }

    private Creator creator() {
        Creator creator = new Creator(CREATOR_OWNER_MEMBER_ID, "크리에이터");
        ReflectionTestUtils.setField(creator, "creatorId", CREATOR_ID);
        return creator;
    }
}
