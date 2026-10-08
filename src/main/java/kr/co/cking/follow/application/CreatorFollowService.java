package kr.co.cking.follow.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.domain.FollowErrorCode;
import kr.co.cking.follow.repository.CreatorFollowRepository;
import kr.co.cking.member.repository.MemberRepository;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Instant;
import java.util.UUID;
import kr.co.cking.common.event.CreatorFollowCreated;
import kr.co.cking.common.repository.DatabaseTime;
import kr.co.cking.common.repository.MemberActivityLock;

/**
 * 크리에이터 팔로우·언팔로우를 처리한다(이슈 #328).
 *
 * <p>둘 다 멱등하다. 이미 팔로우 중인 크리에이터를 다시 팔로우하거나, 팔로우하지 않은 크리에이터를 언팔로우해도
 * 성공한다. Creator는 승인 시에만 생성되므로 Creator가 존재하면 팔로우할 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class CreatorFollowService {

    private final CreatorRepository creatorRepository;
    private final CreatorFollowRepository followRepository;
    private final DatabaseTime databaseTime;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MemberActivityLock activityLock;
    private final CreatorFollowEventService followEvents;

    public void follow(Long memberId, Long creatorId) {
        lockMember(memberId);
        Creator creator = creatorRepository.findById(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (creator.getMemberId().equals(memberId)) {
            throw new BusinessException(FollowErrorCode.SELF_FOLLOW_NOT_ALLOWED);
        }
        if (!followRepository.existsByMemberIdAndCreatorId(memberId, creatorId)) {
            Instant followedAt = databaseTime.now();
            followRepository.insertIfAbsent(memberId, creatorId, followedAt);
            var event = new CreatorFollowCreated(UUID.randomUUID(), memberId, creatorId, followedAt);
            // 원본 이벤트가 없는데 팔로우만 커밋되는 상태를 막는다.
            followEvents.append(event);
            eventPublisher.publishEvent(event);
        }
    }

    public void unfollow(Long memberId, Long creatorId) {
        lockMember(memberId);
        if (!creatorRepository.existsById(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        followRepository.deleteByMemberIdAndCreatorId(memberId, creatorId);
    }

    private void lockMember(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        activityLock.lock(memberId);
    }
}
