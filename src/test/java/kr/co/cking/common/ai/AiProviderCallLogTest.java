package kr.co.cking.common.ai;

import kr.co.cking.common.ai.AiProviderCallLog.NoResponse;
import kr.co.cking.common.ai.AiProviderCallLog.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(OutputCaptureExtension.class)
class AiProviderCallLogTest {

    private static final String SECRET = "provider-secret-4c1d";

    @Test
    void OpenAI_429의_크레딧_부족과_속도_제한을_오류_코드로_구분한다(CapturedOutput output) {
        AiProviderCallLog.Call call = AiProviderCallLog.start(Provider.OPENAI, "quiz_judge", "gpt-6.1-sol");

        call.httpError(error(HttpStatus.TOO_MANY_REQUESTS, """
                {"error": {"message": "You exceeded your current quota %s", "type": "insufficient_quota",
                 "param": null, "code": "insufficient_quota"}}""".formatted(SECRET), "req_quota_1"), 2, false);
        call.httpError(error(HttpStatus.TOO_MANY_REQUESTS, """
                {"error": {"message": "Rate limit reached in organization org-%s on tokens per min",
                 "type": "tokens", "param": null, "code": "rate_limit_exceeded"}}""".formatted(SECRET),
                "req_rate_1"), 1, true);

        assertThat(output.getAll())
                .contains("event=ai_provider_call provider=openai operation=quiz_judge model=gpt-6.1-sol status=429 "
                        + "errorCode=insufficient_quota errorType=insufficient_quota latencyMs=")
                .contains("attempt=2 retry=false requestId=req_quota_1")
                .contains("status=429 errorCode=rate_limit_exceeded errorType=tokens latencyMs=")
                .contains("attempt=1 retry=true requestId=req_rate_1")
                .doesNotContain(SECRET, "exceeded your current quota", "org-", "Rate limit reached");
    }

    @Test
    void Gemini_429는_status와_ErrorInfo_reason만_남기고_details_원문은_남기지_않는다(CapturedOutput output) {
        AiProviderCallLog.start(Provider.GEMINI, "quiz_generation", "gemini-3.8-flash").httpError(
                error(HttpStatus.TOO_MANY_REQUESTS, """
                        {"error": {"code": 429, "message": "Quota exceeded for projects/%s", "status": "RESOURCE_EXHAUSTED",
                         "details": [
                          {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
                           "violations": [{"quotaMetric": "generativelanguage.googleapis.com/%s", "quotaId": "%s"}]},
                          {"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "RATE_LIMIT_EXCEEDED",
                           "domain": "googleapis.com", "metadata": {"consumer": "projects/%s"}},
                          {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "41s"}]}}"""
                                .formatted(SECRET, SECRET, SECRET, SECRET), "req-should-not-appear"), 1, true);

        assertThat(output.getAll())
                .contains("event=ai_provider_call provider=gemini operation=quiz_generation model=gemini-3.8-flash "
                        + "status=429 errorCode=RATE_LIMIT_EXCEEDED errorType=RESOURCE_EXHAUSTED latencyMs=")
                .contains("attempt=1 retry=true")
                .doesNotContain(SECRET, "projects/", "Quota exceeded", "41s", "requestId=");
    }

    @Test
    void 해석할_수_없는_오류_본문은_상태와_기본_정보만_남긴다(CapturedOutput output) {
        AiProviderCallLog.Call call = AiProviderCallLog.start(Provider.OPENAI, "quiz_judge", "gpt-6.1-sol");

        call.httpError(error(HttpStatus.SERVICE_UNAVAILABLE, "<html>" + SECRET + "</html>", null), 1, true);
        call.httpError(error(HttpStatus.BAD_GATEWAY, "", null), 2, false);
        call.httpError(error(HttpStatus.TOO_MANY_REQUESTS, "{\"error\": \"" + SECRET + "\"}", null), 1, false);
        call.httpError(error(HttpStatus.TOO_MANY_REQUESTS, "{\"error\": {\"code\": ", null), 1, false);
        call.httpError(error(HttpStatus.TOO_MANY_REQUESTS, "{\"error\": {\"code\": \"" + "x".repeat(20_000)
                + "\"}}", null), 1, false);

        assertThat(output.getAll())
                .contains("status=503 errorCode=- errorType=- latencyMs=")
                .contains("status=502 errorCode=- errorType=- latencyMs=")
                .doesNotContain(SECRET, "xxxxxxxxxx");
        assertThat(output.getAll().split("status=429 errorCode=- errorType=- latencyMs=", -1)).hasSize(4);
    }

    @Test
    void 형식에_맞지_않는_오류_코드와_요청_ID는_남기지_않는다(CapturedOutput output) {
        AiProviderCallLog.start(Provider.OPENAI, "quiz_judge", "gpt 6 " + SECRET).httpError(
                error(HttpStatus.TOO_MANY_REQUESTS, """
                        {"error": {"type": "bad type %s", "code": "%s"}}""".formatted(SECRET, "c".repeat(65)),
                        "req id with space " + SECRET), 1, false);

        assertThat(output.getAll())
                .contains("provider=openai operation=quiz_judge model=- status=429 errorCode=- errorType=- latencyMs=")
                .doesNotContain(SECRET, "requestId=", "ccccccccccccccccc");
    }

    @Test
    void 응답을_받지_못한_실패는_종류와_예외_이름을_남긴다(CapturedOutput output) {
        AiProviderCallLog.Call call = AiProviderCallLog.start(Provider.GEMINI, "quiz_grounding", "gemini-3.8-flash");

        call.noResponse(NoResponse.TIMEOUT, new SocketTimeoutException("Read timed out " + SECRET), 1, false);
        call.noResponse(NoResponse.NETWORK, new UnknownHostException(SECRET), 1, false);
        call.noResponse(NoResponse.INTERRUPTED, null, 2, false);
        call.invalidResponse(200, 1, false);

        assertThat(output.getAll())
                .contains("status=- errorCode=SocketTimeoutException errorType=timeout latencyMs=")
                .contains("status=- errorCode=UnknownHostException errorType=network latencyMs=")
                .contains("status=- errorCode=- errorType=interrupted latencyMs=")
                .contains("status=200 errorCode=- errorType=invalid_response latencyMs=")
                .doesNotContain(SECRET);
    }

    @Test
    void 로그를_만들다_오류가_나도_던지지_않고_기본_정보를_남긴다(CapturedOutput output) {
        RestClientResponseException broken = new RestClientResponseException("broken", HttpStatusCode.valueOf(500), "Error",
                null, null, null) {
            @Override
            public HttpStatusCode getStatusCode() {
                throw new IllegalStateException(SECRET);
            }
        };

        assertThatCode(() -> AiProviderCallLog.start(Provider.OPENAI, "quiz_judge", "gpt-6.1-sol")
                .httpError(broken, 1, false)).doesNotThrowAnyException();
        assertThat(output.getAll())
                .contains("provider=openai operation=quiz_judge model=gpt-6.1-sol status=- errorCode=- errorType=- "
                        + "latencyMs=- attempt=1 retry=false")
                .doesNotContain(SECRET);
    }

    private static RestClientResponseException error(HttpStatus status, String body, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        if (requestId != null) {
            headers.set("x-request-id", requestId);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return status.is4xxClientError()
                ? HttpClientErrorException.create(status, status.getReasonPhrase(), headers, bytes, StandardCharsets.UTF_8)
                : HttpServerErrorException.create(status, status.getReasonPhrase(), headers, bytes, StandardCharsets.UTF_8);
    }
}
