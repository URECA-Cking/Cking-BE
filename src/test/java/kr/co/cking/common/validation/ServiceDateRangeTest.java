package kr.co.cking.common.validation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceDateRangeTest {

    @Test
    void 어느_시간대에서든_2000년부터_2099년까지를_받는다() {
        for (String zone : new String[]{"Asia/Seoul", "Pacific/Kiritimati", "Etc/GMT+12", "UTC"}) {
            ZoneId id = ZoneId.of(zone);
            assertThat(ServiceDateRange.contains(LocalDateTime.parse("2000-01-01T00:00").atZone(id).toInstant())).isTrue();
            assertThat(ServiceDateRange.contains(LocalDateTime.parse("2099-12-31T23:59").atZone(id).toInstant())).isTrue();
        }
    }

    @Test
    void 범위_밖과_null은_받지_않는다() {
        assertThat(ServiceDateRange.contains(Instant.parse("1999-12-31T09:59:59Z"))).isFalse();
        assertThat(ServiceDateRange.contains(Instant.parse("2100-01-01T12:00:00Z"))).isFalse();
        assertThat(ServiceDateRange.contains(Instant.parse("+10000-12-25T01:01:00Z"))).isFalse();
        assertThat(ServiceDateRange.contains(null)).isFalse();
    }

    @Test
    void 조회_끝값은_범위_안으로_자르고_범위_안의_값은_그대로_둔다() {
        assertThat(ServiceDateRange.clamp(Instant.parse("+10000-01-01T00:00:00Z"))).isEqualTo(ServiceDateRange.MAX_EXCLUSIVE);
        assertThat(ServiceDateRange.clamp(Instant.parse("1945-08-15T00:00:00Z"))).isEqualTo(ServiceDateRange.MIN);
        Instant inside = Instant.parse("2026-10-10T00:00:00Z");
        assertThat(ServiceDateRange.clamp(inside)).isEqualTo(inside);
    }

    @Test
    void 끝_시각은_상한과_같아도_받고_넘으면_받지_않는다() {
        assertThat(ServiceDateRange.containsEnd(ServiceDateRange.MAX_EXCLUSIVE)).isTrue();
        assertThat(ServiceDateRange.containsEnd(ServiceDateRange.MAX_EXCLUSIVE.plusSeconds(1))).isFalse();
        assertThat(ServiceDateRange.containsEnd(ServiceDateRange.MIN.minusSeconds(1))).isFalse();
        assertThat(ServiceDateRange.containsEnd(null)).isFalse();
    }

    @Test
    void 조회_구간이_범위와_조금이라도_겹치는지_판단한다() {
        assertThat(ServiceDateRange.overlaps(Instant.parse("2099-12-01T00:00:00Z"), Instant.parse("2100-01-30T00:00:00Z"))).isTrue();
        assertThat(ServiceDateRange.overlaps(Instant.parse("2101-06-01T00:00:00Z"), Instant.parse("2101-06-02T00:00:00Z"))).isFalse();
        assertThat(ServiceDateRange.overlaps(ServiceDateRange.MAX_EXCLUSIVE, Instant.parse("2100-02-01T00:00:00Z"))).isFalse();
        assertThat(ServiceDateRange.overlaps(Instant.parse("1999-01-01T00:00:00Z"), ServiceDateRange.MIN)).isFalse();
    }
}
