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
}
