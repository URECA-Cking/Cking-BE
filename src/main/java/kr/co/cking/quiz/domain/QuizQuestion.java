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

import java.util.Objects;

/** revision의 문항. {@code questionNo}와 {@code correctOptionIndex}는 0-based다. */
@Entity
@Immutable
@Table(name = "quiz_question")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizQuestion {

    public static final int MAX_QUESTION_LENGTH = 500;
    public static final int MAX_EXPLANATION_LENGTH = 1000;
    public static final int MAX_SOURCE_EVIDENCE_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private Long revisionId;

    @Column(name = "question_no", nullable = false, updatable = false)
    private int questionNo;

    @Column(name = "question_text", nullable = false, updatable = false, length = MAX_QUESTION_LENGTH)
    private String questionText;

    @Column(name = "correct_option_index", nullable = false, updatable = false)
    private int correctOptionIndex;

    @Column(name = "explanation", nullable = false, updatable = false, length = MAX_EXPLANATION_LENGTH)
    private String explanation;

    @Column(name = "source_evidence", nullable = false, updatable = false, length = MAX_SOURCE_EVIDENCE_LENGTH)
    private String sourceEvidence;

    public QuizQuestion(Long revisionId, int questionNo, String questionText, int correctOptionIndex,
                        String explanation, String sourceEvidence) {
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.questionNo = questionNo;
        this.questionText = Objects.requireNonNull(questionText, "questionText");
        this.correctOptionIndex = correctOptionIndex;
        this.explanation = Objects.requireNonNull(explanation, "explanation");
        this.sourceEvidence = Objects.requireNonNull(sourceEvidence, "sourceEvidence");
    }
}
