package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cking.quiz.judge.JudgeUsage;
import kr.co.cking.quiz.judge.QuizJudgeExecution;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * 평가 Job이 보낸 HTTP 요청 하나와 그 사용량(이슈 #529). 응답이 없던 요청도 남겨 재시도 비용이 사라지지 않게 한다.
 * Provider가 주지 않은 값은 0이 아니라 NULL이다. Provider 응답 원문은 저장하지 않는다.
 */
@Entity
@Table(name = "quiz_judge_job_call")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizJudgeJobCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "call_id")
    private Long callId;

    @Column(name = "job_id", nullable = false, updatable = false)
    private Long jobId;

    @Column(name = "call_no", nullable = false, updatable = false)
    private int callNo;

    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(name = "response_id", updatable = false, length = 100)
    private String responseId;

    @Column(name = "response_model", updatable = false, length = 100)
    private String responseModel;

    @Column(name = "input_tokens", updatable = false)
    private Long inputTokens;

    @Column(name = "cached_input_tokens", updatable = false)
    private Long cachedInputTokens;

    @Column(name = "output_tokens", updatable = false)
    private Long outputTokens;

    @Column(name = "reasoning_tokens", updatable = false)
    private Long reasoningTokens;

    @Column(name = "total_tokens", updatable = false)
    private Long totalTokens;

    /** @param callNo 1부터의 요청 순서 */
    public QuizJudgeJobCall(Long jobId, int callNo, QuizJudgeExecution.Exchange exchange) {
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.callNo = callNo;
        this.httpStatus = exchange.httpStatus();
        this.responseId = QuizJudgeJob.limit(exchange.responseId());
        this.responseModel = QuizJudgeJob.limit(exchange.responseModel());
        JudgeUsage usage = exchange.usage();
        if (usage != null) {
            this.inputTokens = usage.inputTokens();
            this.cachedInputTokens = usage.cachedInputTokens();
            this.outputTokens = usage.outputTokens();
            this.reasoningTokens = usage.reasoningTokens();
            this.totalTokens = usage.totalTokens();
        }
    }
}
