package kr.co.cking.mission.application;

import kr.co.cking.mission.domain.MissionCompletion;
import kr.co.cking.mission.repository.MissionCompletionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * mission_completion INSERT 시도를 별도 트랜잭션으로 격리한다.
 *
 * UNIQUE 제약 위반은 MissionCompletionService#complete의 트랜잭션 안에서
 * 바로 캐치하면 안 된다 - flush 실패로 영속성 컨텍스트가 오염된 상태에서 같은
 * 트랜잭션으로 후속 조회를 이어가면 예측 불가능한 동작으로 이어질 수 있다.
 * REQUIRES_NEW로 별도 트랜잭션에 넣어 충돌을 이 안에서만 롤백시키고,
 * 바깥 트랜잭션(호출부의 후속 조회)은 깨끗한 상태로 유지한다.
 *
 * 이 메서드 안에서 DataIntegrityViolationException을 잡지 않는다.
 * saveAndFlush()가 실패하는 순간 이 메서드의 REQUIRES_NEW 트랜잭션은 이미
 * rollback-only로 표시된다 - 예외를 여기서 잡아 정상 반환해도 그 표시는 사라지지
 * 않아서, 이 메서드의 트랜잭션 경계(AOP 프록시)가 커밋을 시도하다가
 * UnexpectedRollbackException을 던진다(실제로 재현된 버그). 예외는 이
 * 메서드 밖으로 전파시켜야 프록시가 "예외 발생 -> 롤백"으로 정상 처리하며,
 * 호출부(MissionCompletionService)가 트랜잭션 경계 밖에서 안전하게 캐치한다.
 */
@Component
@RequiredArgsConstructor
public class MissionCompletionRecorder {

    private final MissionCompletionRepository missionCompletionRepository;

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException UNIQUE 제약 위반 시.
     *         호출부에서 트랜잭션 경계 밖에서 캐치해야 한다(클래스 자바독 참고).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tryInsert(Long memberId, Long creatorId, Long missionId,
                           String periodKey, UUID requestId, LocalDateTime completedAt) {
        missionCompletionRepository.saveAndFlush(
                new MissionCompletion(memberId, creatorId, missionId, periodKey, requestId, completedAt));
    }
}
