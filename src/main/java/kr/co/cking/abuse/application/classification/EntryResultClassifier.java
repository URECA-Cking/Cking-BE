package kr.co.cking.abuse.application.classification;

import java.util.Objects;
import java.util.Optional;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.event.domain.EntryErrorCode;
import kr.co.cking.event.domain.EntryResultCode;
import org.springframework.stereotype.Component;

/** Event Entry 결과를 Abuse Detection 공통 결과 분류로 변환한다. */
@Component
public class EntryResultClassifier {

    /** Entry 결과를 분류하고 입력 범위 위반은 관찰하지 않는다. */
    public Optional<ResultClassification> classify(EntryResultCode resultCode) {
        Objects.requireNonNull(resultCode, "resultCode는 필수입니다.");
        return switch (resultCode) {
            case SUCCESS -> Optional.of(ResultClassification.NEW_SUCCESS);
            case DUPLICATE_REPLAY -> Optional.of(ResultClassification.REPLAY);
            case EVENT_NOT_OPEN, EVENT_CLOSED, INSUFFICIENT_BALANCE, IDEMPOTENCY_CONFLICT ->
                    Optional.of(ResultClassification.BUSINESS_FAILURE);
            case GATE_NOT_LOADED, BALANCE_NOT_LOADED, BALANCE_MAINTENANCE, SYSTEM_ERROR ->
                    Optional.of(ResultClassification.SYSTEM_FAILURE);
            case INVALID_TICKET_COUNT -> Optional.empty();
        };
    }

    /** Entry가 던진 업무 오류를 분류하고 관찰 범위 밖 오류는 빈 결과로 반환한다. */
    public Optional<ResultClassification> classify(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode는 필수입니다.");
        if (errorCode instanceof EntryErrorCode entryErrorCode) {
            return classifyEntryError(entryErrorCode);
        }
        if (errorCode == CommonErrorCode.SYSTEM_ERROR) {
            return Optional.of(ResultClassification.SYSTEM_FAILURE);
        }
        return Optional.empty();
    }

    private Optional<ResultClassification> classifyEntryError(EntryErrorCode errorCode) {
        return switch (errorCode) {
            case EVENT_NOT_OPEN, EVENT_CLOSED, INSUFFICIENT_BALANCE, IDEMPOTENCY_CONFLICT ->
                    Optional.of(ResultClassification.BUSINESS_FAILURE);
            case GATE_NOT_LOADED, BALANCE_NOT_LOADED, BALANCE_MAINTENANCE, SYSTEM_ERROR ->
                    Optional.of(ResultClassification.SYSTEM_FAILURE);
            case INVALID_TICKET_COUNT -> Optional.empty();
        };
    }
}
