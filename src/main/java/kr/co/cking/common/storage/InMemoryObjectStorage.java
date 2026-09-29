package kr.co.cking.common.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryObjectStorage implements ObjectStorage {

    private final ConcurrentMap<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Duration maxPresignedTtl;

    public InMemoryObjectStorage(Duration maxPresignedTtl) {
        this.maxPresignedTtl = Objects.requireNonNull(maxPresignedTtl, "maxPresignedTtl");
    }

    @Override
    public PutResult put(String objectKey, byte[] content, String contentType) {
        requireKey(objectKey);
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(contentType, "contentType");

        byte[] copy = content.clone();
        if (objects.putIfAbsent(objectKey, copy) != null) {
            throw new ObjectAlreadyExistsException(objectKey);
        }
        return new PutResult(objectKey, copy.length, md5Hex(copy));
    }

    @Override
    public byte[] get(String objectKey) {
        requireKey(objectKey);
        byte[] content = objects.get(objectKey);
        if (content == null) {
            throw new ObjectNotFoundException(objectKey);
        }
        return content.clone();
    }

    @Override
    public void delete(String objectKey) {
        requireKey(objectKey);
        objects.remove(objectKey);
    }

    @Override
    public URI presignedGetUrl(String objectKey, Duration ttl) {
        requireKey(objectKey);
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(maxPresignedTtl) > 0) {
            throw new IllegalArgumentException("ttl must be in (0, " + maxPresignedTtl + "]: " + ttl);
        }
        try {
            return new URI("memory", null, "/" + objectKey, "ttl=" + ttl.toSeconds(), null);
        } catch (URISyntaxException e) {
            throw new ObjectStorageException("invalid object key: " + objectKey, e);
        }
    }

    private static void requireKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
    }

    private static String md5Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
