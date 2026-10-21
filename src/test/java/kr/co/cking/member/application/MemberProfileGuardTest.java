package kr.co.cking.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberErrorCode;
import kr.co.cking.member.domain.MemberNickname;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.creator.application.CreatorQueryService;
import org.junit.jupiter.api.Test;

class MemberProfileGuardTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorQueryService creatorQueryService = mock(CreatorQueryService.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MemberProfileGuard guard =
            new MemberProfileGuard(memberRepository, creatorQueryService, meterRegistry, 100, Runnable::run);

    @Test
    void 닉네임이_없는_회원의_공개_활동을_막는다() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(new Member("OAuth", null, null, MemberRole.USER)));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(false);

        assertThatThrownBy(() -> guard.requireConfigured(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.PROFILE_SETUP_REQUIRED);
    }

    @Test
    void 닉네임이_있는_회원은_공개_활동을_할_수_있다() {
        Member member = new Member("OAuth", null, null, MemberRole.USER);
        member.setInitialNickname(MemberNickname.of("fan"));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(false);

        guard.requireConfigured(1L);
    }

    @Test
    void 활동명이_설정된_Creator는_Member_닉네임_없이도_공개_활동을_할_수_있다() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(new Member("OAuth", null, null, MemberRole.USER)));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(true);

        guard.requireConfigured(1L);
    }

    @Test
    void 한_번_통과한_회원은_DB를_다시_조회하지_않는다() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(memberWithNickname()));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(false);

        guard.requireConfigured(1L);
        guard.requireConfigured(1L);

        verify(memberRepository, times(1)).findById(1L);
        verify(creatorQueryService, times(1)).requireActivityNameConfiguredForCreator(1L);
    }

    @Test
    void 거절된_회원은_다음_요청에서도_DB로_다시_확인한다() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(new Member("OAuth", null, null, MemberRole.USER)));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(false);

        assertThatThrownBy(() -> guard.requireConfigured(1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.requireConfigured(1L)).isInstanceOf(BusinessException.class);

        verify(memberRepository, times(2)).findById(1L);
    }

    @Test
    void 닉네임을_설정한_뒤에는_바로_통과한다() {
        Member member = new Member("OAuth", null, null, MemberRole.USER);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(false);
        assertThatThrownBy(() -> guard.requireConfigured(1L)).isInstanceOf(BusinessException.class);

        member.setInitialNickname(MemberNickname.of("fan"));

        guard.requireConfigured(1L);
    }

    @Test
    void 활동명_미설정_Creator는_거절되고_설정한_뒤에는_통과해_기억된다() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(new Member("OAuth", null, null, MemberRole.USER)));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L))
                .willThrow(new BusinessException(CreatorErrorCode.ACTIVITY_NAME_SETUP_REQUIRED))
                .willReturn(true);

        assertThatThrownBy(() -> guard.requireConfigured(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.ACTIVITY_NAME_SETUP_REQUIRED);
        guard.requireConfigured(1L);
        guard.requireConfigured(1L);

        verify(creatorQueryService, times(2)).requireActivityNameConfiguredForCreator(1L);
    }

    @Test
    void 같은_회원의_첫_요청이_동시에_와도_모두_통과하고_이후에는_DB를_읽지_않는다() throws Exception {
        given(memberRepository.findById(1L)).willReturn(Optional.of(memberWithNickname()));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(1L)).willReturn(false);
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    guard.requireConfigured(1L);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        int lookupsDuringRace = mockingDetails(memberRepository).getInvocations().size();

        guard.requireConfigured(1L);

        assertThat(lookupsDuringRace).isBetween(1, threads);
        assertThat(mockingDetails(memberRepository).getInvocations()).hasSize(lookupsDuringRace);
    }

    @Test
    void 상한을_넘으면_밀려난_회원만_DB로_다시_검사한다() {
        MeterRegistry boundedRegistry = new SimpleMeterRegistry();
        MemberProfileGuard bounded =
                new MemberProfileGuard(memberRepository, creatorQueryService, boundedRegistry, 2, Runnable::run);
        given(memberRepository.findById(anyLong())).willReturn(Optional.of(memberWithNickname()));
        given(creatorQueryService.requireActivityNameConfiguredForCreator(anyLong())).willReturn(false);

        for (long memberId = 1; memberId <= 5; memberId++) {
            bounded.requireConfigured(memberId);
        }
        for (long memberId = 1; memberId <= 5; memberId++) {
            bounded.requireConfigured(memberId);
        }

        // 두 번째 바퀴에서 남아 있던 최대 2명만 DB를 건너뛴다.
        verify(memberRepository, atLeast(8)).findById(anyLong());
        assertThat(boundedRegistry.get("cache.size").tag("cache", "member-profile-guard").gauge().value())
                .isLessThanOrEqualTo(2);
    }

    private Member memberWithNickname() {
        Member member = new Member("OAuth", null, null, MemberRole.USER);
        member.setInitialNickname(MemberNickname.of("fan"));
        return member;
    }
}
