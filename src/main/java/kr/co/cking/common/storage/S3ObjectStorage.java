package kr.co.cking.common.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class S3ObjectStorage implements ObjectStorage {

    private static final int PRECONDITION_FAILED = 412;

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration maxPresignedTtl;

    public S3ObjectStorage(S3Client s3Client, S3Presigner presigner, String bucket, Duration maxPresignedTtl) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.presigner = Objects.requireNonNull(presigner, "presigner");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.maxPresignedTtl = Objects.requireNonNull(maxPresignedTtl, "maxPresignedTtl");
    }

    @Override
    public PutResult put(String objectKey, byte[] content, String contentType) {
        ObjectStorageArguments.requireKey(objectKey);
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(contentType, "contentType");

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .ifNoneMatch("*")
                .build();
        try {
            PutObjectResponse response = s3Client.putObject(request, RequestBody.fromBytes(content));
            return new PutResult(objectKey, content.length, response.eTag());
        } catch (S3Exception e) {
            if (e.statusCode() == PRECONDITION_FAILED) {
                throw new ObjectAlreadyExistsException(objectKey);
            }
            throw new ObjectStorageException("put failed: " + objectKey, e);
        } catch (SdkException e) {
            throw new ObjectStorageException("put failed: " + objectKey, e);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        ObjectStorageArguments.requireKey(objectKey);
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
        try {
            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(objectKey);
        } catch (SdkException e) {
            throw new ObjectStorageException("get failed: " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        ObjectStorageArguments.requireKey(objectKey);
        DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build();
        try {
            s3Client.deleteObject(request);
        } catch (SdkException e) {
            throw new ObjectStorageException("delete failed: " + objectKey, e);
        }
    }

    @Override
    public URI presignedGetUrl(String objectKey, Duration ttl) {
        ObjectStorageArguments.requireKey(objectKey);
        ObjectStorageArguments.requirePresignedTtl(ttl, maxPresignedTtl);
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(builder -> builder.bucket(bucket).key(objectKey))
                .build();
        try {
            return presigner.presignGetObject(request).url().toURI();
        } catch (URISyntaxException | SdkException e) {
            throw new ObjectStorageException("presign failed: " + objectKey, e);
        }
    }
}
