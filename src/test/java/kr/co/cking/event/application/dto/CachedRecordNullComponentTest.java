package kr.co.cking.event.application.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import kr.co.cking.event.domain.EventStatus;

/**
 * 캐시 record는 JDK 직렬화로 Redis에 저장된다(EventCacheConfig가 valueSerializer를 지정하지
 * 않아 RedisTemplate 기본값을 쓴다). record 역직렬화는 스트림에 없는 컴포넌트를 기본값(null)로
 * 채운 뒤 표준 생성자를 그대로 호출하므로, 컴포넌트를 추가한 배포의 롤링 중첩 구간에서
 * 구버전 payload를 읽으면 압축 생성자가 null을 받는다. 그때 터지지 않는지 검증한다.
 */
class CachedRecordNullComponentTest {

    @Test
    void CachedEvent는_prizes가_null이어도_빈_목록으로_복원된다() {
        CachedEvent cached = new CachedEvent(1L, 2L, "제목", "설명",
                Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-20T00:00:00Z"),
                1, "RANDOM", "PRIZE_WEIGHTED_V1", EventStatus.OPEN, null);

        assertThat(cached.prizes()).isEmpty();
    }

    @Test
    void CachedEventPage는_events가_null이어도_빈_목록으로_복원된다() {
        CachedEventPage cached = new CachedEventPage(null, 0L);

        assertThat(cached.events()).isEmpty();
    }
}
