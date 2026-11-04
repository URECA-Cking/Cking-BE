package kr.co.cking.creator.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.function.Supplier;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 최종 쓰기 트랜잭션에서 Creator 행을 잠그고 현재 자격을 확인한다. */
@Service
@RequiredArgsConstructor
public class CreatorActivityPolicy {
    private final CreatorRepository creators;
    private final CreatorApplicationLockManager lockManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public Creator requireActiveByCreatorId(Long creatorId) {
        Creator creator = creators.findById(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return lockAndCheck(creator);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Creator requireActiveByMemberId(Long memberId) {
        Creator creator = creators.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        return lockAndCheck(creator);
    }

    private Creator lockAndCheck(Creator creator) {
        entityManager.refresh(creator, LockModeType.PESSIMISTIC_WRITE);
        creator.requireActive();
        return creator;
    }

    /** Redis 작업과 별도 DB 트랜잭션을 여는 명령은 Creator별 세션 잠금으로 상태 변경과 직렬화한다. */
    public <T> T executeActiveOutsideTransaction(Long creatorId, Supplier<T> command) {
        return lockManager.executeWithoutTransaction(CreatorRewardAdmissionService.lockKey(creatorId), 15, () -> {
            creators.findById(creatorId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                    .requireActive();
            return command.get();
        });
    }
}
