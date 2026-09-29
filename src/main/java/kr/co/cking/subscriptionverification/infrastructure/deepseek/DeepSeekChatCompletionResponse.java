package kr.co.cking.subscriptionverification.infrastructure.deepseek;

import java.util.List;
import java.util.Map;

/** DeepSeek Chat Completions 응답에서 Adapter가 필요로 하는 envelope DTO다. */
record DeepSeekChatCompletionResponse(List<DeepSeekChoice> choices, Map<String, Object> usage) {
}

/** 첫 번째 모델 응답과 종료 사유를 표현하는 Provider 전용 DTO다. */
record DeepSeekChoice(DeepSeekResponseMessage message, String finish_reason) {
}

/** DeepSeek가 생성한 JSON 문자열을 표현하는 Provider 전용 DTO다. */
record DeepSeekResponseMessage(String content) {
}
