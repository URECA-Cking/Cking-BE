package kr.co.cking.upload.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@ConditionalOnExpression("!'${cking.image-pipeline.results-queue-url:}'.isEmpty()")
class ImageUploadPipelineConfig {

    // 긴 폴링 20초보다 응답 대기가 길어야 한다. 연결 대기는 S3와 같은 근거(#778)
    @Bean(destroyMethod = "close")
    SqsClient imageUploadSqsClient(@Value("${cking.storage.region:ap-northeast-2}") String region) {
        return SqsClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(Duration.ofMillis(300))
                        .socketTimeout(Duration.ofSeconds(30)))
                .build();
    }
}
