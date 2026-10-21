package kr.co.cking.post.filter;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Lambda 함수 URL(IAM 인증) 호출에 SigV4 서명을 붙인다. 서명 대상은 메서드, 경로, Host, 본문 해시다.
 *
 * <p>Host는 HTTP 클라이언트가 같은 값으로 보내므로 옮기지 않고, 서명이 만든 Authorization과 X-Amz-* 헤더만 요청에 붙인다.
 */
public class AwsSigV4RequestInterceptor implements ClientHttpRequestInterceptor {

    private static final String SERVICE = "lambda";

    private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();
    private final AwsCredentialsProvider credentialsProvider;
    private final String region;
    private final Clock clock;

    public AwsSigV4RequestInterceptor(AwsCredentialsProvider credentialsProvider, String region, Clock clock) {
        this.credentialsProvider = credentialsProvider;
        this.region = region;
        this.clock = clock;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        SdkHttpRequest unsigned = SdkHttpRequest.builder()
                .method(SdkHttpMethod.fromValue(request.getMethod().name()))
                .uri(request.getURI())
                .build();
        SignedRequest signed = signer.sign(signing -> signing
                .identity(credentialsProvider.resolveCredentials())
                .request(unsigned)
                .payload(ContentStreamProvider.fromByteArray(body))
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, SERVICE)
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.SIGNING_CLOCK, clock));

        for (Map.Entry<String, List<String>> header : signed.request().headers().entrySet()) {
            String name = header.getKey();
            if (name.equalsIgnoreCase("Authorization") || name.regionMatches(true, 0, "X-Amz-", 0, 6)) {
                request.getHeaders().put(name, header.getValue());
            }
        }
        return execution.execute(request, body);
    }
}
