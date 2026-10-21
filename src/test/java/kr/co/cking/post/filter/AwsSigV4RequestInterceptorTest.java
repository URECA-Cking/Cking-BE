package kr.co.cking.post.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AwsSigV4RequestInterceptorTest {

    private static final String URL = "https://abc123.lambda-url.ap-northeast-2.on.aws";
    private static final String BODY = "{\"commentId\":1,\"content\":\"본문\"}";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T01:02:03Z"), ZoneOffset.UTC);
    private final StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
            AwsSessionCredentials.create("AKIDEXAMPLE", "secret-example", "session-token-example"));

    @Test
    void 서버_역할_자격_증명으로_lambda_서비스_서명을_붙인다() throws Exception {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(URL)
                .requestInterceptor(new AwsSigV4RequestInterceptor(credentials, "ap-northeast-2", clock));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/moderate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Amz-Date", "20261021T010203Z"))
                .andExpect(header("X-Amz-Security-Token", "session-token-example"))
                .andExpect(header("X-Amz-Content-Sha256", sha256Hex(BODY)))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Authorization"))
                        .startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261021/ap-northeast-2/lambda/aws4_request, ")
                        .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token, ")
                        .containsPattern("Signature=[0-9a-f]{64}$"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        builder.build().post().uri("/moderate").contentType(MediaType.APPLICATION_JSON)
                .body(BODY).retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void 본문이_다르면_서명도_다르다() {
        assertThat(sign("{\"commentId\":1}")).isNotEqualTo(sign("{\"commentId\":2}"));
    }

    private String sign(String body) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(URL)
                .requestInterceptor(new AwsSigV4RequestInterceptor(credentials, "ap-northeast-2", clock));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String[] authorization = new String[1];
        server.expect(requestTo(URL + "/moderate"))
                .andExpect(request -> authorization[0] = request.getHeaders().getFirst("Authorization"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        builder.build().post().uri("/moderate").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toBodilessEntity();
        return authorization[0];
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
