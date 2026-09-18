package kr.co.cking.mission.domain;

import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
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
 * {@link EarnResultCode}를 {@link #from(EarnResultCode)}로 그대로 변환할 뿐이다.
 *
 * <p>{@link kr.co.cking.ticket.application.TicketEarnService#findExisting}의
 * {@link EarnLookupStatus}도 같은 방식으로 {@link #from(EarnLookupStatus)}가 변환한다
 * (Issue #125 — 활성 검증보다 먼저 기존 requestId를 조회해, 미션 종료 후 재시도도
 * 기존 성공 결과를 반환하도록 한다).
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

    /**
     * {@link kr.co.cking.ticket.application.TicketEarnService#findExisting} 조회 결과 중
     * 실패로 처리해야 하는 상태만 변환한다. {@code ALREADY_PROCESSED}/{@code NOT_FOUND}는
     * 실패가 아니므로(각각 기존 성공 결과 반환/신규 처리 진행) 호출하지 않는다.
     */
    public static MissionErrorCode from(EarnLookupStatus status) {
        return switch (status) {
            case REQUEST_ID_CONFLICT -> REQUEST_ID_CONFLICT;
            // Redis 조회 실패 또는 이전 시도가 PROCESSING에 멈춘 상태 — 신규 지급으로
            // 진행하면 안 되고, earn()이 반환할 결과와 동일한 의미(동일 요청으로 재시도)이므로
            // EARN_STATUS_UNKNOWN(504)으로 매핑한다.
            case UNAVAILABLE -> EARN_STATUS_UNKNOWN;
            case ALREADY_PROCESSED, NOT_FOUND ->
                    throw new IllegalArgumentException(status + "는 실패 코드가 아니라 에러로 변환할 수 없습니다.");
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
