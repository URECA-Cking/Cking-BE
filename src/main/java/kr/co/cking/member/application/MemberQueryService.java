package kr.co.cking.member.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.presentation.UserSelectionResponse;
import kr.co.cking.member.presentation.UserSummary;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberQueryService {

    private final MemberRepository memberRepository;

    public List<UserSummary> findUsers() {
        return memberRepository.findAll().stream()
                .map(UserSummary::from)
                .toList();
    }

    public UserSelectionResponse selectUser(Long userId) {
        Member member = memberRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return UserSelectionResponse.from(member);
    }
}
