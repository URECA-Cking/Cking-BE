package kr.co.cking.creator.application;

import kr.co.cking.creator.repository.CreatorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Member ID가 Creator 계정에 연결됐는지 읽기 전용으로 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorQueryService {

    private final CreatorRepository creatorRepository;

    public boolean isCreatorMember(Long memberId) {
        return creatorRepository.existsByMemberId(memberId);
    }
}
