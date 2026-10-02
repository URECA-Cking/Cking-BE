package kr.co.cking.abuse.application.classification;

import java.util.Objects;
import java.util.Optional;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.springframework.stereotype.Component;

/** Mission 완료 결과를 Abuse Detection 공통 결과 분류로 변환한다. */
@Component
public class MissionResultClassifier {

    /** Ticket EARN 결과를 분류한다. 모든 EARN 결과는 Mission Observation 대상이다. */
    public Optional<ResultClassification> classify(EarnResultCode resultCode) {
        Objects.requireNonNull(resultCode, "resultCode는 필수입니다.");
        return Optional.of(switch (resultCode) {
            case EARN_ACCEPTED -> ResultClassification.NEW_SUCCESS;
            case ALREADY_PROCESSED -> ResultClassification.REPLAY;
            case DUPLICATE_MISSION, REQUEST_ID_CONFLICT -> ResultClassification.BUSINESS_FAILURE;
            case EARN_PROCESSING_FAILED, EARN_STATUS_UNKNOWN, BALANCE_MAINTENANCE ->
                    ResultClassification.SYSTEM_FAILURE;
        });
    }

    /** Mission 완료가 던진 업무 오류를 분류하고 관찰 범위 밖 오류는 빈 결과로 반환한다. */
    public Optional<ResultClassification> classify(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode는 필수입니다.");
        if (errorCode instanceof MissionErrorCode missionErrorCode) {
            return classifyMissionError(missionErrorCode);
        }
        if (errorCode == CommonErrorCode.SYSTEM_ERROR) {
            return Optional.of(ResultClassification.SYSTEM_FAILURE);
        }
        return Optional.empty();
    }

    private Optional<ResultClassification> classifyMissionError(MissionErrorCode errorCode) {
        return switch (errorCode) {
            case MISSION_INACTIVE, DUPLICATE_MISSION, REQUEST_ID_CONFLICT ->
                    Optional.of(ResultClassification.BUSINESS_FAILURE);
            case EARN_PROCESSING_FAILED, EARN_STATUS_UNKNOWN, BALANCE_MAINTENANCE ->
                    Optional.of(ResultClassification.SYSTEM_FAILURE);
            case MISSION_NOT_FOUND, MISSION_REQUIRES_VERIFICATION -> Optional.empty();
        };
    }
}
