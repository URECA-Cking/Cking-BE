package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 게시글 작성 주체인 Creator를 Member ID로 찾는다. Member·Creator는 읽기만 한다. */
@Component
@RequiredArgsConstructor
class PostAuthorLookup {

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;

    /** 없는 Member는 404, Member는 있지만 Creator가 아니면 403 — 존재 여부와 자격을 구분한다. */
    Creator requireCreator(Long memberId) {
        memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }
}
