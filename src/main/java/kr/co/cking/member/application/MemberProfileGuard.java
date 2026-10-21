package kr.co.cking.member.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 공개 활동을 생성하는 도메인이 재사용하는 닉네임 설정 선행 검증이다. */
@Component
public class MemberProfileGuard {

    private final MemberRepository memberRepository;
    private final CreatorQueryService creatorQueryService;
    // 닉네임·활동명은 설정 뒤 미설정으로 돌아가지 않으므로, 통과한 회원은 DB를 다시 읽지 않는다(#706).
    // 상한을 넘어 밀려난 회원만 다음 요청에서 DB로 다시 검사한다.
    private final Cache<Long, Boolean> configuredMembers;

    @Autowired
    public MemberProfileGuard(
            MemberRepository memberRepository,
            CreatorQueryService creatorQueryService,
            MeterRegistry meterRegistry,
            @Value("${cking.member.profile-guard.max-size:100000}") long maxSize
    ) {
        this(memberRepository, creatorQueryService, meterRegistry, maxSize, ForkJoinPool.commonPool());
    }

    MemberProfileGuard(
            MemberRepository memberRepository,
            CreatorQueryService creatorQueryService,
            MeterRegistry meterRegistry,
            long maxSize,
            Executor maintenanceExecutor
    ) {
        this.memberRepository = memberRepository;
        this.creatorQueryService = creatorQueryService;
        this.configuredMembers = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .executor(maintenanceExecutor)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, configuredMembers, "member-profile-guard");
    }

    public void requireConfigured(Long memberId) {
        if (configuredMembers.getIfPresent(memberId) != null) {
            return;
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!creatorQueryService.requireActivityNameConfiguredForCreator(memberId)) {
            member.requireProfileConfigured();
        }
        configuredMembers.put(memberId, Boolean.TRUE);
    }
}
