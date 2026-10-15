package kr.co.cking.quiz.judge;

import java.math.BigDecimal;
import java.util.List;

/**
 * 평가 한 번(attempt)에서 실제로 보낸 HTTP 요청의 기록. 성공·실패와 관계없이 남겨 재시도 비용이 사라지지 않게 한다.
 *
 * @param requestedModel 설정으로 요청한 모델
 * @param exchanges      보낸 HTTP 요청 순서대로의 기록
 * @param cost           단가가 설정되지 않았으면 null
 */
public record QuizJudgeExecution(String requestedModel, List<Exchange> exchanges, CostEstimate cost) {

    public QuizJudgeExecution {
        exchanges = List.copyOf(exchanges);
    }

    public int httpRequestCount() {
        return exchanges.size();
    }

    /**
     * 모든 요청에서 input·output token을 관측했을 때만 true다. 응답이 없던 요청이 있으면 false다. 비용 계산에 필요한
     * 값이 모두 맞는지는 {@link CostEstimate#complete()}로 따로 본다.
     */
    public boolean usageComplete() {
        return exchanges.stream().allMatch(exchange -> exchange.usage() != null
                && exchange.usage().inputTokens() != null && exchange.usage().outputTokens() != null);
    }

    /** 마지막으로 응답에 담겨 온 모델. 응답 본문을 받지 못했으면 null이다. */
    public String responseModel() {
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            if (exchanges.get(index).responseModel() != null) {
                return exchanges.get(index).responseModel();
            }
        }
        return null;
    }

    /**
     * HTTP 요청 하나.
     *
     * @param httpStatus    응답 상태. 시간 초과·네트워크 오류로 응답이 없으면 null
     * @param responseId    Provider 응답 ID. 없으면 null
     * @param responseModel Provider가 응답에 적은 모델. 없으면 null
     * @param usage         관측한 사용량. 없으면 null
     */
    public record Exchange(Integer httpStatus, String responseId, String responseModel, JudgeUsage usage) {
    }

    /**
     * 관측한 사용량과 설정 단가로 계산한 추정 비용. Provider 청구액이 아니다.
     *
     * @param usd            {@link JudgeUsage#isCostComputable()}인 요청만으로 계산한 값. 그런 요청이 없으면 null
     * @param pricingVersion 계산에 쓴 단가 버전
     * @param complete       모든 요청을 계산했는지. false면 실제 비용은 {@code usd}보다 클 수 있다
     */
    public record CostEstimate(BigDecimal usd, String pricingVersion, boolean complete) {
    }
}
