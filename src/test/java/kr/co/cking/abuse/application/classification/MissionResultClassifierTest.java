package kr.co.cking.abuse.application.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.stream.Stream;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class MissionResultClassifierTest {

    private final MissionResultClassifier classifier = new MissionResultClassifier();

    @ParameterizedTest
    @MethodSource("earnResults")
    void EARN_결과를_공통_분류로_변환한다(
            EarnResultCode resultCode,
            ResultClassification expected
    ) {
        assertThat(classifier.classify(resultCode)).contains(expected);
    }

    static Stream<Arguments> earnResults() {
        return Stream.of(
                Arguments.of(EarnResultCode.EARN_ACCEPTED, ResultClassification.NEW_SUCCESS),
                Arguments.of(EarnResultCode.ALREADY_PROCESSED, ResultClassification.REPLAY),
                Arguments.of(EarnResultCode.DUPLICATE_MISSION, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(EarnResultCode.REQUEST_ID_CONFLICT, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(EarnResultCode.EARN_PROCESSING_FAILED, ResultClassification.SYSTEM_FAILURE),
                Arguments.of(EarnResultCode.EARN_STATUS_UNKNOWN, ResultClassification.SYSTEM_FAILURE),
                Arguments.of(EarnResultCode.BALANCE_MAINTENANCE, ResultClassification.SYSTEM_FAILURE)
        );
    }

    @ParameterizedTest
    @MethodSource("missionErrors")
    void Mission_업무_오류를_공통_분류로_변환한다(
            MissionErrorCode errorCode,
            ResultClassification expected
    ) {
        assertThat(classifier.classify(errorCode)).contains(expected);
    }

    static Stream<Arguments> missionErrors() {
        return Stream.of(
                Arguments.of(MissionErrorCode.MISSION_INACTIVE, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(MissionErrorCode.DUPLICATE_MISSION, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(MissionErrorCode.REQUEST_ID_CONFLICT, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(MissionErrorCode.EARN_PROCESSING_FAILED, ResultClassification.SYSTEM_FAILURE),
                Arguments.of(MissionErrorCode.EARN_STATUS_UNKNOWN, ResultClassification.SYSTEM_FAILURE),
                Arguments.of(MissionErrorCode.BALANCE_MAINTENANCE, ResultClassification.SYSTEM_FAILURE)
        );
    }

    @ParameterizedTest
    @EnumSource(value = MissionErrorCode.class, names = {
            "MISSION_NOT_FOUND",
            "MISSION_REQUIRES_VERIFICATION"
    })
    void 리소스_확인과_별도_인증_오류는_관찰하지_않는다(MissionErrorCode errorCode) {
        assertThat(classifier.classify(errorCode)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = CommonErrorCode.class, names = {
            "VALIDATION_FAILED",
            "RESOURCE_NOT_FOUND",
            "UNAUTHORIZED",
            "FORBIDDEN"
    })
    void 공통_인증_검증_리소스_오류는_관찰하지_않는다(CommonErrorCode errorCode) {
        assertThat(classifier.classify(errorCode)).isEmpty();
    }

    @Test
    void 공통_SYSTEM_ERROR는_시스템_실패다() {
        assertThat(classifier.classify(CommonErrorCode.SYSTEM_ERROR))
                .contains(ResultClassification.SYSTEM_FAILURE);
    }

    @Test
    void null_결과는_분류할_수_없다() {
        assertThatNullPointerException().isThrownBy(() -> classifier.classify((EarnResultCode) null));
        assertThatNullPointerException().isThrownBy(() -> classifier.classify((kr.co.cking.common.exception.ErrorCode) null));
    }
}
