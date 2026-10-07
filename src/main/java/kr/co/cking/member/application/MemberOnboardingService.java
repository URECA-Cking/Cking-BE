package kr.co.cking.member.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Member의 온보딩 완료 상태를 변경한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class MemberOnboardingService {

    private final MemberRepository memberRepository;

    /** 온보딩 완료를 멱등하게 기록한다. 이미 완료된 회원도 성공으로 처리한다. */
    public void complete(Long memberId) {
        memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .completeOnboarding();
    }
}
