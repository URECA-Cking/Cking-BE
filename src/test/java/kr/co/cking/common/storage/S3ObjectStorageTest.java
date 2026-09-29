package kr.co.cking.common.storage;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
}
