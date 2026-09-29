package kr.co.cking.common.storage;

import java.net.URI;
import java.time.Duration;

public interface ObjectStorage {

    /** 같은 키가 이미 있으면 덮어쓰지 않고 {@link ObjectAlreadyExistsException}을 던진다. */
    PutResult put(String objectKey, byte[] content, String contentType);

    /** 키가 없으면 {@link ObjectNotFoundException}을 던진다. */
    byte[] get(String objectKey);

    /** 없는 키를 지워도 예외를 던지지 않는다. */
    void delete(String objectKey);

    /** 유효 시간이 0 이하이거나 상한을 넘으면 {@link IllegalArgumentException}을 던진다. */
    URI presignedGetUrl(String objectKey, Duration ttl);
}
