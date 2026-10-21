package kr.co.cking.member.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 공개 활동을 생성하는 도메인이 재사용하는 닉네임 설정 선행 검증이다. */
@Component
@RequiredArgsConstructor
public class MemberProfileGuard {

    private final MemberRepository memberRepository;
    private final CreatorQueryService creatorQueryService;
    // 닉네임·활동명은 설정 뒤 미설정으로 돌아가지 않으므로, 통과한 회원은 DB를 다시 읽지 않는다(#706).
    private final Set<Long> configuredMemberIds = ConcurrentHashMap.newKeySet();

    public void requireConfigured(Long memberId) {
        if (configuredMemberIds.contains(memberId)) {
            return;
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!creatorQueryService.requireActivityNameConfiguredForCreator(memberId)) {
            member.requireProfileConfigured();
        }
        configuredMemberIds.add(memberId);
    }
}
