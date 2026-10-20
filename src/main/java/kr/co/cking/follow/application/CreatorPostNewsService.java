package kr.co.cking.follow.application;

import java.time.Instant;
import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.dto.CreatorPostNewsView;
import kr.co.cking.follow.domain.FollowErrorCode;
import kr.co.cking.follow.repository.CreatorFollowRepository;
import kr.co.cking.follow.repository.CreatorPostNewsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 팔로우별 게시글 읽음 기록과 홈 새 글 조회를 소유한다. */
@Service
@RequiredArgsConstructor
public class CreatorPostNewsService {

    private final CreatorRepository creatorRepository;
    private final CreatorFollowRepository followRepository;
    private final CreatorPostNewsRepository newsRepository;

    /** 서버 DB 시각과 조건부 갱신으로 읽음 시각의 단조 증가를 보장한다. */
    @Transactional
    public Instant markRead(Long memberId, Long creatorId) {
        if (!creatorRepository.existsById(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        followRepository.findForReadUpdate(memberId, creatorId)
                .orElseThrow(() -> new BusinessException(FollowErrorCode.FOLLOW_REQUIRED));
        followRepository.markPostsRead(memberId, creatorId);
        return followRepository.findLastReadAt(memberId, creatorId);
    }

    /** 현재 팔로우 관계와 볼 수 있는 최신 글을 한 번의 DB 질의로 조회한다. */
    @Transactional(readOnly = true)
    public List<CreatorPostNewsView> findMine(Long memberId) {
        return newsRepository.findByMemberId(memberId);
    }
}
