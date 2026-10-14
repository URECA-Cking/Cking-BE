package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.Objects;

/**
 * revision의 문항. {@code questionNo}와 {@code correctOptionIndex}는 0-based다.
 *
 * <p>AI 생성 revision의 문항은 변경 유형과 원본·직전 참조가 없다. Creator가 구성한 revision의 문항은
 * {@link QuizQuestionChangeType}과 원본 AI 문항·부모 revision의 직전 문항 참조를 가진다. 빈 sourceEvidence는 근거 없음이다.
 */
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

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", updatable = false, length = 20)
    private QuizQuestionChangeType changeType;

    @Column(name = "origin_question_id", updatable = false)
    private Long originQuestionId;

    @Column(name = "previous_question_id", updatable = false)
    private Long previousQuestionId;

    /** AI 생성 revision의 원본 문항. */
    public QuizQuestion(Long revisionId, int questionNo, String questionText, int correctOptionIndex,
                        String explanation, String sourceEvidence) {
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.questionNo = questionNo;
        this.questionText = Objects.requireNonNull(questionText, "questionText");
        this.correctOptionIndex = correctOptionIndex;
        this.explanation = Objects.requireNonNull(explanation, "explanation");
        this.sourceEvidence = Objects.requireNonNull(sourceEvidence, "sourceEvidence");
    }

    /**
     * Creator가 구성한 revision의 문항. 변경 유형과 참조의 조합은 DB CHECK와 같다. Creator가 수정·추가한 문항의
     * sourceEvidence가 없거나(null) 공백뿐이면 빈 문자열로 저장한다. AI_UNCHANGED는 원본 근거를 그대로 받는다.
     */
    public static QuizQuestion composed(Long revisionId, int questionNo, String questionText, int correctOptionIndex,
                                        String explanation, String sourceEvidence, QuizQuestionChangeType changeType,
                                        Long originQuestionId, Long previousQuestionId) {
        Objects.requireNonNull(changeType, "changeType");
        boolean added = changeType == QuizQuestionChangeType.CREATOR_ADDED;
        if (added ? originQuestionId != null : originQuestionId == null || previousQuestionId == null) {
            throw new IllegalArgumentException("변경 유형과 원본·직전 문항 참조가 맞지 않습니다.");
        }
        String evidence = changeType != QuizQuestionChangeType.AI_UNCHANGED
                && (sourceEvidence == null || sourceEvidence.isBlank()) ? "" : sourceEvidence;
        QuizQuestion question = new QuizQuestion(revisionId, questionNo, questionText, correctOptionIndex,
                explanation, evidence);
        question.changeType = changeType;
        question.originQuestionId = originQuestionId;
        question.previousQuestionId = previousQuestionId;
        return question;
    }
}
