package kr.co.cking.quiz.grounding;

import kr.co.cking.quiz.generation.QuizProviderException;

/**
 * 시간 초과를 제외한 영상 Grounding 실패. 재시도할 수 있는 오류는 Adapter 안에서 이미 재시도했으므로 호출자는
 * 다시 호출하지 않는다.
 *
 * <p>Provider 응답 원문·API key·contentText가 새지 않도록 메시지는 고정 문구만 쓰고 cause를 붙이지 않는다.
 */
public class VideoGroundingException extends QuizProviderException {

    private final Failure failure;

    public VideoGroundingException(Failure failure) {
        super(failure.message);
        this.failure = failure;
    }

    public Failure getFailure() {
        return failure;
    }

    public enum Failure {
        /** API key가 설정되지 않아 호출하지 않았다. */
        NOT_CONFIGURED("영상 Grounding 설정이 없습니다."),
        /** Provider가 요청을 거부했다(429를 제외한 4xx, 비공개 영상 등). */
        REJECTED("영상 Grounding 요청이 거부되었습니다."),
        /** 429·5xx 재시도를 소진했거나 네트워크 오류가 났다. */
        PROVIDER_ERROR("영상 Grounding 요청이 실패했습니다."),
        /** 응답이 완료되지 않았거나 형식·계약을 지키지 않았다. */
        INVALID_RESPONSE("영상 Grounding 응답이 올바르지 않습니다.");

        private final String message;

        Failure(String message) {
            this.message = message;
        }
    }
}
