package kr.co.cking.common.config;

import java.time.Duration;
import kr.co.cking.common.storage.InMemoryObjectStorage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.common.storage.S3ObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class ObjectStorageConfig {

    // 게시글 이미지 10분이 들어가고, EC2 역할 자격 증명 교체 주기보다 짧은 값
    private static final Duration MAX_PRESIGNED_TTL = Duration.ofHours(1);

    @Bean
    @ConditionalOnProperty(name = "cking.storage.type", havingValue = "memory", matchIfMissing = true)
    public ObjectStorage inMemoryObjectStorage() {
        return new InMemoryObjectStorage(MAX_PRESIGNED_TTL);
    }

    @Configuration
    @ConditionalOnProperty(name = "cking.storage.type", havingValue = "s3")
    static class S3 {

        @Bean(destroyMethod = "close")
        S3Client s3Client(ObjectStorageProperties properties) {
            return S3Client.builder().region(Region.of(properties.region())).build();
        }

        @Bean(destroyMethod = "close")
        S3Presigner s3Presigner(ObjectStorageProperties properties) {
            return S3Presigner.builder().region(Region.of(properties.region())).build();
        }

        @Bean
        ObjectStorage s3ObjectStorage(S3Client s3Client, S3Presigner presigner, ObjectStorageProperties properties) {
            return new S3ObjectStorage(s3Client, presigner, properties.bucket(), MAX_PRESIGNED_TTL);
        }
    }
}
