package kr.co.cking.common.config;

import java.time.Duration;
import kr.co.cking.common.storage.InMemoryObjectStorage;
import kr.co.cking.common.storage.ObjectStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObjectStorageConfig {

    // S3 서명 방식(SigV4)이 허용하는 최대 유효 시간. S3 구현에서 실제 상한을 정한다.
    private static final Duration MAX_PRESIGNED_TTL = Duration.ofDays(7);

    @Bean
    public ObjectStorage objectStorage() {
        return new InMemoryObjectStorage(MAX_PRESIGNED_TTL);
    }
}
