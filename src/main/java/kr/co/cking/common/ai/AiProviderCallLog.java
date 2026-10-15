package kr.co.cking.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.regex.Pattern;

/**
 * 외부 AI Provider 호출 실패를 진단용 {@code key=value} 로그 한 줄로 남긴다(이슈 #606).
 *
 * <p>필드: {@code event=ai_provider_call provider operation model status errorCode errorType latencyMs attempt retry},
 * 응답 헤더에 있으면 {@code requestId}. 오류 본문에서는 허용한 코드 값만 꺼낸다. OpenAI는 {@code error.code}·
 * {@code error.type}, Gemini는 {@code error.status}와 {@code details}의 {@code ErrorInfo.reason}이다. 응답 원문,
 * 자유 형식 오류 메시지, 요청 본문, API key는 남기지 않는다. 형식에 맞지 않는 값과 해석할 수 없는 본문은 {@code -}다.
 *
 * <p>호출자의 재시도·실패 분류·예외 처리에 영향을 주지 않도록 로그를 만들다 난 오류는 밖으로 던지지 않는다.
 */
@Slf4j
public final class AiProviderCallLog {

    private static final String NONE = "-";
    private static final int MAX_ERROR_BODY_BYTES = 16 * 1024;
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9_.:/-]{1,100}");
    private static final String GOOGLE_ERROR_INFO = "type.googleapis.com/google.rpc.ErrorInfo";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private AiProviderCallLog() {
    }

    public enum Provider {
        OPENAI("openai", "x-request-id"),
        GEMINI("gemini", null);

        private final String value;
        private final String requestIdHeader;

        Provider(String value, String requestIdHeader) {
            this.value = value;
            this.requestIdHeader = requestIdHeader;
        }
    }

    /** 응답을 받지 못한 실패의 종류. */
    public enum NoResponse {
        TIMEOUT("timeout"),
        NETWORK("network"),
        INTERRUPTED("interrupted");

        private final String value;

        NoResponse(String value) {
            this.value = value;
        }
    }

    /** 요청 한 번을 보내기 직전에 만들어 소요 시간을 잰다. */
    public static Call start(Provider provider, String operation, String model) {
        return new Call(provider, operation, model, System.nanoTime());
    }

    public record Call(Provider provider, String operation, String model, long startedNanos) {

        /** Provider가 오류 상태(4xx·5xx)로 응답했다. */
        public void httpError(RestClientResponseException exception, int attempt, boolean retry) {
            try {
                int status = exception.getStatusCode().value();
                String[] codes = errorCodes(provider, exception.getResponseBodyAsByteArray());
                write(String.valueOf(status), codes[0], codes[1], attempt, retry,
                        requestId(provider, exception.getResponseHeaders()));
            } catch (RuntimeException ignored) {
                writeBasic(attempt, retry);
            }
        }

        /** 응답을 받지 못했다. errorCode는 예외 클래스 이름이다. */
        public void noResponse(NoResponse kind, Throwable cause, int attempt, boolean retry) {
            try {
                write(NONE, cause == null ? NONE : code(cause.getClass().getSimpleName()), kind.value, attempt, retry,
                        null);
            } catch (RuntimeException ignored) {
                writeBasic(attempt, retry);
            }
        }

        /** 정상 상태로 응답했지만 계약에 맞지 않아 실패로 처리했다. */
        public void invalidResponse(int status, int attempt, boolean retry) {
            try {
                write(String.valueOf(status), NONE, "invalid_response", attempt, retry, null);
            } catch (RuntimeException ignored) {
                writeBasic(attempt, retry);
            }
        }

        private void write(String status, String errorCode, String errorType, int attempt, boolean retry,
                           String requestId) {
            long latencyMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
            log.warn("event=ai_provider_call provider={} operation={} model={} status={} errorCode={} errorType={} "
                            + "latencyMs={} attempt={} retry={}{}", provider.value, code(operation), safeModel(model),
                    status, errorCode, errorType, latencyMs, attempt, retry,
                    requestId == null ? "" : " requestId=" + requestId);
        }

        private void writeBasic(int attempt, boolean retry) {
            try {
                log.warn("event=ai_provider_call provider={} operation={} model={} status=- errorCode=- errorType=- "
                        + "latencyMs=- attempt={} retry={}", provider.value, code(operation), safeModel(model), attempt,
                        retry);
            } catch (RuntimeException ignored) {
                // 로그를 남기지 못해도 호출자의 실패 처리는 그대로 진행한다.
            }
        }
    }

    /** [errorCode, errorType]. 본문이 없거나 너무 크거나 해석할 수 없으면 둘 다 {@code -}다. */
    private static String[] errorCodes(Provider provider, byte[] body) {
        String[] none = {NONE, NONE};
        if (body == null || body.length == 0 || body.length > MAX_ERROR_BODY_BYTES) {
            return none;
        }
        JsonNode error;
        try {
            error = JSON.readTree(body).path("error");
        } catch (RuntimeException exception) {
            return none;
        }
        if (!error.isObject()) {
            return none;
        }
        return switch (provider) {
            case OPENAI -> new String[]{code(text(error.path("code"))), code(text(error.path("type")))};
            case GEMINI -> new String[]{code(errorInfoReason(error.path("details"))), code(text(error.path("status")))};
        };
    }

    private static String errorInfoReason(JsonNode details) {
        for (JsonNode detail : details) {
            if (GOOGLE_ERROR_INFO.equals(text(detail.path("@type")))) {
                return text(detail.path("reason"));
            }
        }
        return null;
    }

    private static String requestId(Provider provider, HttpHeaders headers) {
        if (provider.requestIdHeader == null || headers == null) {
            return null;
        }
        String value = headers.getFirst(provider.requestIdHeader);
        return value != null && REQUEST_ID.matcher(value).matches() ? value : null;
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }

    private static String code(String value) {
        return value != null && CODE.matcher(value).matches() ? value : NONE;
    }

    private static String safeModel(String value) {
        return value != null && MODEL.matcher(value).matches() ? value : NONE;
    }
}
