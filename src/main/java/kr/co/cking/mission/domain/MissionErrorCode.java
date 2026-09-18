package kr.co.cking.mission.domain;

import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 미션 완료 API의 실패 케이스. {@code EARN_ACCEPTED}/{@code ALREADY_PROCESSED}는
 * 실패가 아니므로(각각 202/200 정상 응답) 이 enum에 포함하지 않는다 — 통합 API
 * 명세 v2.5 §4.2 매핑표 기준.
 *
 * <p>{@code DUPLICATE_MISSION}/{@code REQUEST_ID_CONFLICT}는 이제 이 API가 직접
 * 판정하지 않는다 — {@code ticket-earn.lua}가 멱등키·중복 적립 가드를 원자적으로
 * 처리한 뒤 {@link kr.co.cking.ticket.application.TicketEarnService#earn}이 반환하는
 * {@link EarnResultCode}를 {@link #from}으로 그대로 변환할 뿐이다.
 */
@RequiredArgsConstructor
public enum MissionErrorCode implements ErrorCode {

    MISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "미션을 찾을 수 없습니다."),
    MISSION_INACTIVE(HttpStatus.CONFLICT, "현재 활성 상태가 아닌 미션입니다."),
    DUPLICATE_MISSION(HttpStatus.CONFLICT, "해당 기간에 이미 완료한 미션입니다."),
    REQUEST_ID_CONFLICT(HttpStatus.CONFLICT, "동일 requestId로 다른 요청 내용이 전달되었습니다."),
    EARN_PROCESSING_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "응모권 적립 처리에 실패했습니다. 잠시 후 다시 시도해주세요."),
    EARN_STATUS_UNKNOWN(HttpStatus.GATEWAY_TIMEOUT, "응모권 적립 처리 결과를 확인할 수 없습니다. 동일 요청으로 재시도해주세요.");

    private final HttpStatus status;
    private final String message;

    /**
     * {@link kr.co.cking.ticket.application.TicketEarnService#earn} 결과 중 실패 코드만
     * 변환한다. {@code EARN_ACCEPTED}/{@code ALREADY_PROCESSED}는 실패가 아니므로 호출하지 않는다.
     */
    public static MissionErrorCode from(EarnResultCode code) {
        return switch (code) {
            case DUPLICATE_MISSION -> DUPLICATE_MISSION;
            case REQUEST_ID_CONFLICT -> REQUEST_ID_CONFLICT;
            case EARN_PROCESSING_FAILED -> EARN_PROCESSING_FAILED;
            case EARN_STATUS_UNKNOWN -> EARN_STATUS_UNKNOWN;
            case EARN_ACCEPTED, ALREADY_PROCESSED ->
                    throw new IllegalArgumentException(code + "는 실패 코드가 아니라 에러로 변환할 수 없습니다.");
        };
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
