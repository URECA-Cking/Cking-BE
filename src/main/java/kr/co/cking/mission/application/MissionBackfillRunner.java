package kr.co.cking.mission.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 이 기능이 배포되기 전에 이미 승인된 Creator는 자동 생성 대상이 아니었으므로,
 * 기존 Creator 전체를 대상으로 누락된 기본 미션을 채운다(이슈 #185). 스케줄러나
 * 앱 시작 시점에 자동 연결하지 않는다 — {@code TicketCompensationService}의 수동
 * 보정과 같은 패턴으로, 운영자가 배포 시점에 한 번 명시적으로 호출하는 용도다.
 *
 * <p>{@link MissionInitializationService#initializeCreatorInNewTransaction}을
 * (self-invocation이 아니라) 이 클래스에서 프록시로 호출해 Creator마다 독립된
 * 트랜잭션으로 처리한다. 한 Creator에서 실패해도 그 실패만 잡아 기록하고
 * 다음 Creator로 계속 진행한다 — 전체가 한 트랜잭션이었다면 실패 하나가 이미
 * 처리된 나머지까지 전부 롤백시켰을 것이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissionBackfillRunner {

    private final CreatorRepository creatorRepository;
    private final MissionInitializationService missionInitializationService;

    public void backfillAllCreators() {
        List<Long> creatorIds = creatorRepository.findAll().stream()
                .map(Creator::getCreatorId)
                .toList();
        backfillCreators(creatorIds);
    }

    /** 특정 Creator 목록만 백필할 때 사용한다(테스트, 또는 부분 재시도). */
    public void backfillCreators(List<Long> creatorIds) {
        for (Long creatorId : creatorIds) {
            try {
                missionInitializationService.initializeCreatorInNewTransaction(creatorId);
            } catch (RuntimeException e) {
                log.warn("Creator 기본 미션 백필 실패, 다음 Creator로 계속 진행합니다. creatorId={}", creatorId, e);
            }
        }
    }
}
