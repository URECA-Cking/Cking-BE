package kr.co.cking.calendar.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleTimeZonesTest {

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Seoul", "America/New_York", "Etc/UTC", "UTC", "GMT"})
    void IANA_Zone_ID_레지스트리에_있는_지역명은_유효하다(String timeZone) {
        assertThat(ScheduleTimeZones.isValid(timeZone)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+09:00", "EST", "Asia/Seou", ""})
    void 고정_오프셋_구식_축약형_오타는_거부한다(String timeZone) {
        assertThat(ScheduleTimeZones.isValid(timeZone)).isFalse();
    }

    @Test
    void null은_거부한다() {
        assertThat(ScheduleTimeZones.isValid(null)).isFalse();
    }

    @Test
    void 정규화는_getId_값을_반환한다() {
        assertThat(ScheduleTimeZones.normalize("Asia/Seoul")).isEqualTo("Asia/Seoul");
    }
}
