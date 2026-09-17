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
 * <p>{@code DUPLICATE_MISSION}/{@code REQUEST_ID_CONFLICT}는 {@code mission_completion}의
 * business key/request_id UNIQUE 제약을 우리 쪽에서 직접 판정해 발생시킨다.
 * {@link kr.co.cking.ticket.application.TicketEarnService#earn}이 같은 이름의
 * 코드를 반환하는 경로는 이슈 #30(자비님, Business Key 가드) 완료 후에만 실제로
 * 도달한다 — {@link #from}은 그 두 경로를 동일하게 매핑하기 위한 것이다.
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
