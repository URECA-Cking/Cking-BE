package kr.co.cking.abuse.application.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import kr.co.cking.mission.domain.CommonMissionType;
import kr.co.cking.mission.domain.MissionType;
import org.junit.jupiter.api.Test;

class MissionBusinessKeyFactoryTest {

    private final MissionBusinessKeyFactory factory = new MissionBusinessKeyFactory();

    @Test
    void Creator_LIKE는_UTC_날짜별로_다른_키를_만든다() {
        MissionBusinessKey beforeMidnight = factory.forCreator(
                MissionType.LIKE, 17L, 5L, 103L, Instant.parse("2026-10-01T23:59:59Z"));
        MissionBusinessKey afterMidnight = factory.forCreator(
                MissionType.LIKE, 17L, 5L, 103L, Instant.parse("2026-10-02T00:00:00Z"));

        assertThat(beforeMidnight).isEqualTo(new MissionBusinessKey(
                "MISSION:CREATOR:DAILY:17:5:103:2026-10-01", "2026-10-01"));
        assertThat(afterMidnight).isEqualTo(new MissionBusinessKey(
                "MISSION:CREATOR:DAILY:17:5:103:2026-10-02", "2026-10-02"));
    }

    @Test
    void Creator_SHARE는_날짜와_무관한_ONCE_키를_만든다() {
        MissionBusinessKey first = factory.forCreator(
                MissionType.SHARE, 17L, 5L, 103L, Instant.parse("2026-10-01T23:59:59Z"));
        MissionBusinessKey nextDay = factory.forCreator(
                MissionType.SHARE, 17L, 5L, 103L, Instant.parse("2026-10-02T00:00:00Z"));

        assertThat(first).isEqualTo(new MissionBusinessKey("MISSION:CREATOR:ONCE:17:5:103", null));
        assertThat(nextDay).isEqualTo(first);
    }

    @Test
    void 공용_ATTENDANCE는_Creator_없이_UTC_날짜를_키에_넣는다() {
        MissionBusinessKey key = factory.forCommon(
                CommonMissionType.ATTENDANCE, 17L, 103L, Instant.parse("2026-10-01T15:00:00Z"));

        assertThat(key).isEqualTo(new MissionBusinessKey(
                "MISSION:COMMON:DAILY:17:103:2026-10-01", "2026-10-01"));
    }

    @Test
    void 관찰_대상이_아닌_Creator_미션에는_키를_만들지_않는다() {
        for (MissionType type : new MissionType[] {
                MissionType.ATTENDANCE, MissionType.YOUTUBE_SUBSCRIPTION
        }) {
            assertThatIllegalArgumentException().isThrownBy(() -> factory.forCreator(
                    type, 17L, 5L, 103L, Instant.parse("2026-10-01T00:00:00Z")));
        }
    }

    @Test
    void 식별자와_기간_시각의_입력_경계를_검증한다() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        assertThatIllegalArgumentException().isThrownBy(() -> factory.forCreator(
                MissionType.LIKE, 0L, 5L, 103L, now));
        assertThatIllegalArgumentException().isThrownBy(() -> factory.forCreator(
                MissionType.LIKE, 17L, -1L, 103L, now));
        assertThatIllegalArgumentException().isThrownBy(() -> factory.forCreator(
                MissionType.LIKE, 17L, 5L, 0L, now));
        assertThatIllegalArgumentException().isThrownBy(() -> factory.forCommon(
                CommonMissionType.ATTENDANCE, 0L, 103L, now));
        assertThatIllegalArgumentException().isThrownBy(() -> factory.forCommon(
                CommonMissionType.ATTENDANCE, 17L, -1L, now));
        assertThatNullPointerException().isThrownBy(() -> factory.forCreator(
                MissionType.LIKE, 17L, 5L, 103L, null));
        assertThatNullPointerException().isThrownBy(() -> factory.forCommon(
                CommonMissionType.ATTENDANCE, 17L, 103L, null));
    }
}
