package kr.co.cking.snapshot.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum SnapshotErrorCode implements ErrorCode {

    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."),
    EVENT_NOT_CLOSED(HttpStatus.CONFLICT, "CLOSED 상태의 이벤트만 Snapshot을 생성할 수 있습니다."),
    SNAPSHOT_NOT_FOUND(HttpStatus.NOT_FOUND, "공식 Snapshot을 찾을 수 없습니다."),
    SNAPSHOT_HASH_MISMATCH(HttpStatus.CONFLICT, "Snapshot Hash가 일치하지 않습니다.");

    private final HttpStatus status;
    private final String message;

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
