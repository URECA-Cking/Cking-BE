package kr.co.cking.common.storage;

import java.time.Duration;
import java.util.Objects;

final class ObjectStorageArguments {

    private static final Duration MIN_PRESIGNED_TTL = Duration.ofSeconds(1);

    private ObjectStorageArguments() {
    }

    static void requireKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
    }

    static void requirePresignedTtl(Duration ttl, Duration max) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.compareTo(MIN_PRESIGNED_TTL) < 0 || ttl.toNanosPart() != 0 || ttl.compareTo(max) > 0) {
            throw new IllegalArgumentException(
                    "ttl must be whole seconds in [" + MIN_PRESIGNED_TTL + ", " + max + "]: " + ttl);
        }
    }
}
