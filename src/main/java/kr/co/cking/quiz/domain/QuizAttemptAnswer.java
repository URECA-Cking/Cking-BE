package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 문항별 제출 답안과 전체 풀이 스냅샷. 원본 문항의 변경과 독립적으로 보존한다. */
@Entity
@Immutable
@Table(name = "quiz_attempt_answer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizAttemptAnswer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long answerId;
    @Column(nullable = false, updatable = false) private Long attemptId;
    @Column(nullable = false, updatable = false) private int questionNo;
    @Column(nullable = false, updatable = false, length = 500) private String questionText;
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT") private String optionsJson;
    @Column(nullable = false, updatable = false) private int optionCount;
    @Column(nullable = false, updatable = false) private int selectedOptionIndex;
    @Column(nullable = false, updatable = false) private int correctOptionIndex;
    @Column(nullable = false, updatable = false) private boolean correct;
    @Column(nullable = false, updatable = false, length = 1000) private String explanation;
    @Column(nullable = false, updatable = false, length = 1000) private String sourceEvidence;

    public QuizAttemptAnswer(Long attemptId, int questionNo, String questionText, String optionsJson,
            int optionCount, int selectedOptionIndex, int correctOptionIndex, String explanation, String sourceEvidence) {
        this.attemptId = attemptId;
        this.questionNo = questionNo;
        this.questionText = questionText;
        this.optionsJson = optionsJson;
        this.optionCount = optionCount;
        this.selectedOptionIndex = selectedOptionIndex;
        this.correctOptionIndex = correctOptionIndex;
        this.correct = selectedOptionIndex == correctOptionIndex;
        this.explanation = explanation;
        this.sourceEvidence = sourceEvidence;
    }
}
