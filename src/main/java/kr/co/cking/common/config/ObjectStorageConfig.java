package kr.co.cking.common.config;

import java.time.Duration;
import kr.co.cking.common.storage.InMemoryObjectStorage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.common.storage.S3ObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class ObjectStorageConfig {

    // 게시글 이미지 10분이 들어가고, EC2 역할 자격 증명 교체 주기보다 짧은 값
    private static final Duration MAX_PRESIGNED_TTL = Duration.ofHours(1);

    // 개발 서버 실측 S3 호출 최악 약 0.7초. 전체 제한은 시도 두 번 + 재시도 간격 (#773)
    static final Duration S3_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration S3_CALL_TIMEOUT = Duration.ofSeconds(7);
    // 제한 시간은 진행 중인 연결 시도를 끊지 못하고, S3 주소는 IP 8개로 풀린다. 실측 연결 최대 5.6ms (#778)
    static final Duration S3_CONNECT_TIMEOUT = Duration.ofMillis(300);
    static final int S3_RESOLVED_ADDRESSES = 8;

    @Bean
    @ConditionalOnProperty(name = "cking.storage.type", havingValue = "memory", matchIfMissing = true)
    public ObjectStorage inMemoryObjectStorage() {
        return new InMemoryObjectStorage(MAX_PRESIGNED_TTL);
    }

    @Configuration
    @ConditionalOnProperty(name = "cking.storage.type", havingValue = "s3")
    static class S3 {

        // 로컬 S3 호환 저장소는 서명을 검사하지 않지만 SDK는 서명할 자격 증명이 필요하다.
        private static final StaticCredentialsProvider LOCAL_CREDENTIALS =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"));

        @Bean(destroyMethod = "close")
        S3Client s3Client(ObjectStorageProperties properties) {
            S3ClientBuilder builder = S3Client.builder()
                    .region(Region.of(properties.region()))
                    .httpClientBuilder(Apache5HttpClient.builder().connectionTimeout(S3_CONNECT_TIMEOUT))
                    .overrideConfiguration(c -> c
                            .apiCallAttemptTimeout(S3_CALL_ATTEMPT_TIMEOUT)
                            .apiCallTimeout(S3_CALL_TIMEOUT));
            if (properties.endpoint() != null) {
                builder.endpointOverride(properties.endpoint())
                        .forcePathStyle(true)
                        .credentialsProvider(LOCAL_CREDENTIALS);
            }
            return builder.build();
        }

        @Bean(destroyMethod = "close")
        S3Presigner s3Presigner(ObjectStorageProperties properties) {
            S3Presigner.Builder builder = S3Presigner.builder().region(Region.of(properties.region()));
            if (properties.endpoint() != null) {
                builder.endpointOverride(properties.endpoint())
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .credentialsProvider(LOCAL_CREDENTIALS);
            }
            return builder.build();
        }

        @Bean
        ObjectStorage s3ObjectStorage(S3Client s3Client, S3Presigner presigner, ObjectStorageProperties properties) {
            return new S3ObjectStorage(s3Client, presigner, properties.bucket(), MAX_PRESIGNED_TTL);
        }
    }
}
