package kr.co.cking.mission.application;

import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creator에게 기본 미션(출석·좋아요)을 준비한다(이슈 #185). 1차 MVP는 두 미션 모두
 * 크리에이터당 1개 고정(reward 1장, 상시 활성)이라 Creator가 직접 만드는 API를 두지
 * 않고, 이 서비스가 빠진 유형만 채워 넣는 방식으로 대신한다.
 *
 * <p>이미 있는 유형은 건너뛴다({@code uk_mission_creator_type} 위반 방지) — 아래
 * 두 진입점 모두에서 재실행해도 안전하도록 하기 위해서다.
 *
 * <p>진입점이 전파(propagation)가 다른 두 개로 나뉜다:
 * <ul>
 *   <li>{@link #initializeDefaultMissions}(기본값 REQUIRED) — Creator 승인 흐름
 *   (시스템4) 안에서 호출한다. 그 트랜잭션에 그대로 참여해, 미션 생성이 실패하면
 *   승인도 함께 롤백된다.</li>
 *   <li>{@link #initializeCreatorInNewTransaction}({@code REQUIRES_NEW}) —
 *   {@link MissionBackfillRunner}가 기존 Creator를 순회하며 호출한다. Creator마다
 *   독립된 트랜잭션이라, 한 Creator에서 실패해도 앞서 처리된 다른 Creator의 커밋은
 *   영향받지 않는다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MissionInitializationService {

    private static final List<MissionType> DEFAULT_TYPES = List.of(MissionType.ATTENDANCE, MissionType.LIKE);
    private static final int DEFAULT_REWARD_AMOUNT = 1;

    private final MissionRepository missionRepository;

    /** 신규 승인 Creator에 누락된 기본 미션만 현재 트랜잭션에 참여시켜 생성한다. */
    public void initializeDefaultMissions(Long creatorId) {
        Set<MissionType> existingTypes = missionRepository.findByCreatorIdAndTypeIn(creatorId, DEFAULT_TYPES)
                .stream()
                .map(Mission::getType)
                .collect(Collectors.toSet());

        DEFAULT_TYPES.stream()
                .filter(type -> !existingTypes.contains(type))
                .forEach(type -> missionRepository.save(
                        new Mission(creatorId, type, DEFAULT_REWARD_AMOUNT, null, null)));
    }

    /**
     * 기존 Creator 백필 한 건을 독립 트랜잭션으로 초기화한다.
     *
     * {@link MissionBackfillRunner} 전용 진입점. 반드시 그 클래스처럼 스프링이 관리하는
     * 다른 빈에서, 프록시를 거쳐 호출해야 {@code REQUIRES_NEW}가 실제로 적용된다 —
     * 같은 클래스 안에서 이 메서드를 self-invocation하면 프록시를 우회해 조용히
     * 무시된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void initializeCreatorInNewTransaction(Long creatorId) {
        initializeDefaultMissions(creatorId);
    }
}
