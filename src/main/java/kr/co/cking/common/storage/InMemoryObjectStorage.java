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
        ObjectStorageArguments.requireKey(objectKey);
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
        ObjectStorageArguments.requireKey(objectKey);
        byte[] content = objects.get(objectKey);
        if (content == null) {
            throw new ObjectNotFoundException(objectKey);
        }
        return content.clone();
    }

    @Override
    public void delete(String objectKey) {
        ObjectStorageArguments.requireKey(objectKey);
        objects.remove(objectKey);
    }

    @Override
    public URI presignedGetUrl(String objectKey, Duration ttl) {
        ObjectStorageArguments.requireKey(objectKey);
        ObjectStorageArguments.requirePresignedTtl(ttl, maxPresignedTtl);
        try {
            return new URI("memory", null, "/" + objectKey, "ttl=" + ttl.toSeconds(), null);
        } catch (URISyntaxException e) {
            throw new ObjectStorageException("invalid object key: " + objectKey, e);
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
