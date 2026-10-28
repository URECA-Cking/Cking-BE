package kr.co.cking.common.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public interface ObjectStorage {

    /** 같은 키가 이미 있으면 덮어쓰지 않고 {@link ObjectAlreadyExistsException}을 던진다. */
    PutResult put(String objectKey, byte[] content, String contentType);

    /** 키가 없으면 {@link ObjectNotFoundException}을 던진다. */
    byte[] get(String objectKey);

    /** 없는 키를 지워도 예외를 던지지 않는다. */
    void delete(String objectKey);

    /** 유효 시간이 1초 미만이거나, 초 단위가 아니거나, 상한을 넘으면 {@link IllegalArgumentException}을 던진다. */
    URI presignedGetUrl(String objectKey, Duration ttl);

    /** 형식·크기·메타데이터를 서명에 넣은 PUT 주소. 다른 크기나 형식으로 올리면 저장소가 거절한다. */
    PresignedUpload presignedPut(String objectKey, String contentType, long contentLength,
                                 Map<String, String> metadata, Duration ttl);
}
