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

    /**
     * Creator는 팬 닉네임 대신 활동명으로 공개 활동하므로, Creator인 경우에만 활동명 설정을 요구한다.
     * Creator가 아닌 Member는 호출자가 별도의 Member 프로필 규칙을 적용한다.
     */
    public boolean requireActivityNameConfiguredForCreator(Long memberId) {
        return creatorRepository.findByMemberId(memberId)
                .map(creator -> {
                    creator.requireActivityNameConfigured();
                    return true;
                })
                .orElse(false);
    }
}
