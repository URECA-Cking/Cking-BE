package kr.co.cking.abuse.application.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.stream.Stream;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.domain.EntryErrorCode;
import kr.co.cking.event.domain.EntryResultCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class EntryResultClassifierTest {

    private final EntryResultClassifier classifier = new EntryResultClassifier();

    @ParameterizedTest
    @MethodSource("entryResults")
    void Entry_결과를_공통_분류로_변환한다(
            EntryResultCode resultCode,
            ResultClassification expected
    ) {
        assertThat(classifier.classify(resultCode)).contains(expected);
    }

    static Stream<Arguments> entryResults() {
        return Stream.of(
                Arguments.of(EntryResultCode.SUCCESS, ResultClassification.NEW_SUCCESS),
                Arguments.of(EntryResultCode.DUPLICATE_REPLAY, ResultClassification.REPLAY),
                Arguments.of(EntryResultCode.EVENT_NOT_OPEN, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(EntryResultCode.EVENT_CLOSED, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(EntryResultCode.INSUFFICIENT_BALANCE, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(EntryResultCode.IDEMPOTENCY_CONFLICT, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(EntryResultCode.GATE_NOT_LOADED, ResultClassification.SYSTEM_FAILURE),
                Arguments.of(EntryResultCode.BALANCE_NOT_LOADED, ResultClassification.SYSTEM_FAILURE),
                Arguments.of(EntryResultCode.BALANCE_MAINTENANCE, ResultClassification.SYSTEM_FAILURE),
                Arguments.of(EntryResultCode.SYSTEM_ERROR, ResultClassification.SYSTEM_FAILURE)
        );
    }

    @ParameterizedTest
    @MethodSource("entryErrors")
    void Entry_업무_오류를_공통_분류로_변환한다(
            EntryErrorCode errorCode,
            ResultClassification expected
    ) {
        assertThat(classifier.classify(errorCode)).contains(expected);
    }

    static Stream<Arguments> entryErrors() {
        return Stream.of(
                Arguments.of(EntryErrorCode.EVENT_NOT_OPEN, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(EntryErrorCode.EVENT_CLOSED, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(EntryErrorCode.INSUFFICIENT_BALANCE, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(EntryErrorCode.IDEMPOTENCY_CONFLICT, ResultClassification.BUSINESS_FAILURE),
                Arguments.of(EntryErrorCode.GATE_NOT_LOADED, ResultClassification.SYSTEM_FAILURE),
                Arguments.of(EntryErrorCode.BALANCE_NOT_LOADED, ResultClassification.SYSTEM_FAILURE),
                Arguments.of(EntryErrorCode.BALANCE_MAINTENANCE, ResultClassification.SYSTEM_FAILURE),
                Arguments.of(EntryErrorCode.SYSTEM_ERROR, ResultClassification.SYSTEM_FAILURE)
        );
    }

    @Test
    void 잘못된_응모권_수량은_관찰하지_않는다() {
        assertThat(classifier.classify(EntryResultCode.INVALID_TICKET_COUNT)).isEmpty();
        assertThat(classifier.classify(EntryErrorCode.INVALID_TICKET_COUNT)).isEmpty();
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
        assertThatNullPointerException().isThrownBy(() -> classifier.classify((EntryResultCode) null));
        assertThatNullPointerException().isThrownBy(() -> classifier.classify((kr.co.cking.common.exception.ErrorCode) null));
    }
}
