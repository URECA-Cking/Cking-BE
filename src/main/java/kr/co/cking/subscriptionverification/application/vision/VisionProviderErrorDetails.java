package kr.co.cking.subscriptionverification.application.vision;

/**
 * 외부 Vision Provider HTTP 실패에서 원문 body 없이 운영 로그에 남길 수 있는 최소 진단 정보다.
 *
 * <p>Provider message, 요청 body, 이미지·API key 등 비정형 원문은 이 객체에 넣지 않는다.
 */
public record VisionProviderErrorDetails(
        int httpStatus,
        int providerCode,
        String providerStatus,
        String providerReason
) {

    public static final int UNKNOWN_CODE = -1;
    public static final String UNKNOWN = "UNKNOWN";

    public static VisionProviderErrorDetails unavailable() {
        return new VisionProviderErrorDetails(0, UNKNOWN_CODE, UNKNOWN, UNKNOWN);
    }
}
