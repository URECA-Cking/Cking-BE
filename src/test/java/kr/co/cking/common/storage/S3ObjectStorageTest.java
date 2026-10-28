package kr.co.cking.common.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Testcontainers(disabledWithoutDocker = true)
class S3ObjectStorageTest extends ObjectStorageContractTest {

    private static final String BUCKET = "test-bucket";

    @Container
    private static final S3MockContainer S3_MOCK = new S3MockContainer("5.2.3").withInitialBuckets(BUCKET);

    private static S3Client s3Client;
    private static S3Presigner presigner;
    private static S3ObjectStorage storage;

    @BeforeAll
    static void setUp() {
        URI endpoint = URI.create(S3_MOCK.getHttpEndpoint());
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                .build();
        presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        storage = new S3ObjectStorage(s3Client, presigner, BUCKET, MAX_PRESIGNED_TTL);
    }

    @AfterAll
    static void tearDown() {
        presigner.close();
        s3Client.close();
    }

    @Override
    protected ObjectStorage storage() {
        return storage;
    }

    /** #780: 브라우저가 다른 크기·형식·최소 크기로 바꿔 올리지 못하게 모두 서명에 넣는다. */
    @Test
    void 사전_서명_PUT은_형식_크기_메타데이터를_서명하고_그대로_올리면_저장된다() throws Exception {
        byte[] content = {1, 2, 3, 4};
        PresignedUpload presigned = storage.presignedPut("raw/post-images/1/a.jpg", "image/jpeg", content.length,
                java.util.Map.of("min-width", "200", "min-height", "200"), java.time.Duration.ofMinutes(5));

        assertThat(presigned.url().getQuery())
                .contains("X-Amz-SignedHeaders=content-length;content-type;host;x-amz-meta-min-height;x-amz-meta-min-width");
        assertThat(presigned.headers()).containsEntry("content-type", "image/jpeg")
                .containsEntry("x-amz-meta-min-width", "200").containsEntry("x-amz-meta-min-height", "200")
                .doesNotContainKeys("host", "content-length");

        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(presigned.url())
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(content));
        presigned.headers().forEach(request::header);
        int status = java.net.http.HttpClient.newHttpClient()
                .send(request.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();

        assertThat(status).isEqualTo(200);
        assertThat(storage.get("raw/post-images/1/a.jpg")).isEqualTo(content);
    }
}
