package kr.co.cking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.s3.S3Client;

class ObjectStorageConfigTest {

    /** #773: 제한 시간이 없으면 S3가 응답하지 않을 때 업로드가 약 1분 붙잡혀 이미지 처리 자리를 모두 막는다. */
    @Test
    void S3_호출은_시도당과_전체_제한_시간이_있다() {
        ObjectStorageProperties properties =
                new ObjectStorageProperties("s3", "bucket", "ap-northeast-2", URI.create("http://localhost:9090"));

        try (S3Client client = new ObjectStorageConfig.S3().s3Client(properties)) {
            ClientOverrideConfiguration config = client.serviceClientConfiguration().overrideConfiguration();

            assertThat(config.apiCallAttemptTimeout()).contains(ObjectStorageConfig.S3_CALL_ATTEMPT_TIMEOUT);
            assertThat(config.apiCallTimeout()).contains(ObjectStorageConfig.S3_CALL_TIMEOUT);
            assertThat(ObjectStorageConfig.S3_CALL_TIMEOUT).isGreaterThan(ObjectStorageConfig.S3_CALL_ATTEMPT_TIMEOUT);
        }
    }

    /** #778: 연결이 안 되면 IP 8개를 차례로 시도하는데, 그동안은 제한 시간이 끊지 못한다. 한 시도 안에 끝나야 한다. */
    @Test
    void 연결_대기는_풀린_주소를_모두_시도해도_시도당_제한_시간_안에_끝난다() {
        assertThat(ObjectStorageConfig.S3_CONNECT_TIMEOUT.multipliedBy(ObjectStorageConfig.S3_RESOLVED_ADDRESSES))
                .isLessThan(ObjectStorageConfig.S3_CALL_ATTEMPT_TIMEOUT);
    }
}
