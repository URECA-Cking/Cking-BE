package kr.co.cking.abuse.application.context;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import kr.co.cking.mission.domain.CommonMissionType;
import kr.co.cking.mission.domain.MissionType;
import org.springframework.stereotype.Component;

/** 관찰 대상 Mission의 Abuse 전용 Business Key를 만든다. Ticket EARN Guard 키와는 별개다. */
@Component
public class MissionBusinessKeyFactory {

    /** periodAt은 Mission이 EARN periodKey를 확정할 때 사용한 시각이어야 한다. */
    public MissionBusinessKey forCreator(
            MissionType type, Long userId, Long creatorId, Long missionId, Instant periodAt
    ) {
        Objects.requireNonNull(type, "Mission type은 필수입니다.");
        requirePositive(userId, "userId");
        requirePositive(creatorId, "creatorId");
        requirePositive(missionId, "missionId");
        Objects.requireNonNull(periodAt, "periodAt은 필수입니다.");

        return switch (type) {
            case LIKE -> {
                String periodKey = utcDate(periodAt);
                yield new MissionBusinessKey(
                        "MISSION:CREATOR:DAILY:%d:%d:%d:%s"
                                .formatted(userId, creatorId, missionId, periodKey),
                        periodKey
                );
            }
            case SHARE -> new MissionBusinessKey(
                    "MISSION:CREATOR:ONCE:%d:%d:%d".formatted(userId, creatorId, missionId),
                    null
            );
            case ATTENDANCE, YOUTUBE_SUBSCRIPTION -> throw new IllegalArgumentException(
                    "Abuse v1에서 관찰하지 않는 Creator Mission 유형입니다: " + type);
        };
    }

    public MissionBusinessKey forCommon(
            CommonMissionType type, Long userId, Long missionId, Instant periodAt
    ) {
        Objects.requireNonNull(type, "Common Mission type은 필수입니다.");
        requirePositive(userId, "userId");
        requirePositive(missionId, "missionId");
        Objects.requireNonNull(periodAt, "periodAt은 필수입니다.");

        return switch (type) {
            case ATTENDANCE -> {
                String periodKey = utcDate(periodAt);
                yield new MissionBusinessKey(
                        "MISSION:COMMON:DAILY:%d:%d:%s".formatted(userId, missionId, periodKey),
                        periodKey
                );
            }
        };
    }

    private String utcDate(Instant periodAt) {
        return DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(periodAt);
    }
}
