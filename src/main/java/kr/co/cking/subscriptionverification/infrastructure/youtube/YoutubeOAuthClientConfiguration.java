package kr.co.cking.subscriptionverification.infrastructure.youtube;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** YouTube OAuth·채널 조회의 연결 제한 시간을 설정한다. */
@Configuration(proxyBeanMethods = false)
public class YoutubeOAuthClientConfiguration {

    @Bean("youtubeOAuthRestClient")
    RestClient youtubeOAuthRestClient(
            @Value("${cking.youtube-oauth.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${cking.youtube-oauth.read-timeout:PT10S}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(positive(connectTimeout));
        factory.setReadTimeout(positive(readTimeout));
        return RestClient.builder().requestFactory(factory).build();
    }

    private Duration positive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("YouTube 요청 제한 시간은 양수여야 합니다.");
        }
        return value;
    }
}
