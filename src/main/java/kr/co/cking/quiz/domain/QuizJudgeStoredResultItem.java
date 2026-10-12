package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cking.quiz.judge.JudgeRubric;
import kr.co.cking.quiz.judge.JudgeVerdict;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

/** 평가 결과의 문항 하나 × rubric 항목 하나의 판정과 근거(이슈 #529). reason은 검증을 통과한 원문 그대로다. */
@Entity
@Table(name = "quiz_judge_result_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizJudgeStoredResultItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "result_id", nullable = false, updatable = false)
    private Long resultId;

    @Column(name = "question_no", nullable = false, updatable = false)
    private int questionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "rubric", nullable = false, updatable = false, length = 40)
    private JudgeRubric rubric;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, updatable = false, length = 20)
    private JudgeVerdict verdict;

    @Column(name = "reason", nullable = false, updatable = false, length = 1000)
    private String reason;

    public QuizJudgeStoredResultItem(Long resultId, int questionNo, JudgeRubric rubric, JudgeVerdict verdict,
                                     String reason) {
        this.resultId = Objects.requireNonNull(resultId, "resultId");
        this.questionNo = questionNo;
        this.rubric = Objects.requireNonNull(rubric, "rubric");
        this.verdict = Objects.requireNonNull(verdict, "verdict");
        this.reason = Objects.requireNonNull(reason, "reason");
    }
}
