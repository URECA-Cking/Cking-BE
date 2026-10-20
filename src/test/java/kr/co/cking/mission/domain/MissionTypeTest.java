package kr.co.cking.mission.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MissionTypeTest {

    @Test
    void 유형별_표시_이름을_돌려주고_없거나_모르는_유형은_null이다() {
        assertThat(MissionType.displayNameOf("ATTENDANCE")).isEqualTo("출석체크");
        assertThat(MissionType.displayNameOf("LIKE")).isEqualTo("좋아요");
        assertThat(MissionType.displayNameOf("SHARE")).isEqualTo("공유");
        assertThat(MissionType.displayNameOf("YOUTUBE_SUBSCRIPTION")).isEqualTo("유튜브 구독");
        assertThat(MissionType.displayNameOf(null)).isNull();
        assertThat(MissionType.displayNameOf("UNKNOWN")).isNull();
    }
}
