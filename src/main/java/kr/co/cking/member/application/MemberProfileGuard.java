package kr.co.cking.member.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 공개 활동을 생성하는 도메인이 재사용하는 닉네임 설정 선행 검증이다. */
@Component
@RequiredArgsConstructor
public class MemberProfileGuard {

    private final MemberRepository memberRepository;

    public void requireConfigured(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        member.requireProfileConfigured();
    }
}
