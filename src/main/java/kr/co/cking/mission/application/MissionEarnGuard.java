package kr.co.cking.mission.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import kr.co.cking.mission.domain.MissionType;

/**
 * 동일 사용자·미션·크리에이터·날짜의 중복 요청을 DB까지 가기 전에 미리 표시하는
 * 1차 가드. 키 형식과 TTL은 팀 협의 결과를 그대로 따른다:
 * {@code mission:earn-guard:{userId}:{missionType}:{creatorId}:{yyyyMMdd}}, TTL 25시간
 * (최소 기준 충족 + 자정 경계 재시도 커버, 최종 방어는 {@code mission_completion}의
 * Business Key UNIQUE가 담당하므로 48시간까지 유지할 필요는 없다는 결론).
 *
 * <p>이 가드는 <b>관측용/최적화용이며 판정 자체를 바꾸지 않는다</b> — 획득 실패 시에도
 * {@link MissionCompletionService}는 기존 DB 기반 판정(findByRequestId/existsByBusinessKey)을
 * 그대로 수행한다. Redis가 재기동 등으로 키를 잃어버려도 DB UNIQUE 제약이 최종 방어선이므로
 * 정합성에는 영향이 없다. 날짜 세그먼트는 {@code periodKey}(UTC, {@code yyyy-MM-dd})와
 * 별개로 {@code yyyyMMdd} 포맷을 쓴다 — 팀 문서의 원래 키 형식을 그대로 유지하되,
 * periodKey와 동일하게 UTC 기준 날짜로 계산한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MissionEarnGuard {

    private static final Duration TTL = Duration.ofHours(25);
    private static final DateTimeFormatter GUARD_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    private final StringRedisTemplate redisTemplate;

    /** @return 이번 요청이 해당 조합의 첫 시도로 추정되면 true. Redis 오류 시에도 true로 안전하게 통과시킨다. */
    public boolean tryAcquire(Long userId, MissionType missionType, Long creatorId, LocalDate utcDate) {
        String key = keyOf(userId, missionType, creatorId, utcDate);
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException e) {
            // Redis 장애는 최종 판정에 영향을 주지 않아야 한다 — DB UNIQUE가 진짜 방어선이다.
            log.warn("mission earn guard unavailable, falling back to DB-only dedup: key={}", key, e);
            return true;
        }
    }

    private String keyOf(Long userId, MissionType missionType, Long creatorId, LocalDate utcDate) {
        return "mission:earn-guard:%d:%s:%d:%s".formatted(
                userId, missionType.name(), creatorId, utcDate.format(GUARD_DATE_FORMAT));
    }
}
