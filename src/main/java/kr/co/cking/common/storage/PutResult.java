package kr.co.cking.common.storage;

import java.util.Objects;

public record PutResult(String objectKey, long size, String eTag) {

    public PutResult {
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(eTag, "eTag");
    }
}
