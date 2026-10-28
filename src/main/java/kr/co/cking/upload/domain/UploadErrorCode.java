package kr.co.cking.upload.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum UploadErrorCode implements ErrorCode {
    INVALID_UPLOAD_REQUEST(HttpStatus.BAD_REQUEST, "업로드할 수 없는 형식이나 크기입니다."),
    UPLOAD_NOT_FOUND(HttpStatus.NOT_FOUND, "업로드를 찾을 수 없습니다.");

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
