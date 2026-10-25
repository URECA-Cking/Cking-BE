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
}
