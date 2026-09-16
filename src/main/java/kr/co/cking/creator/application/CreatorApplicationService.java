package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.CreatorApplicationRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CreatorApplicationService {

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final CreatorApplicationRepository applicationRepository;

    public CreatorApplication apply(Long memberId) {
        // 신청 처리에는 Member의 상세 정보가 필요 없으므로 존재 여부만 확인한다.
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        if (creatorRepository.existsByMemberId(memberId)) {
            throw new BusinessException(CreatorErrorCode.INVALID_STATE);
        }
        // 처리 중인 신청은 새로 만들지 않고 기존 결과를 반환한다.
        return applicationRepository.findFirstByMemberIdAndStatusOrderByIdDesc(
                        memberId,
                        CreatorApplicationStatus.PENDING
                )
                .orElseGet(() -> applicationRepository.save(new CreatorApplication(memberId)));
    }
}
