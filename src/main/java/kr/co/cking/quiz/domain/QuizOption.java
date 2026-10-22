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

/** 문항의 보기. {@code optionNo}는 0-based 표시 순서다. */
@Entity
@Immutable
@Table(name = "quiz_option")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizOption {

    public static final int MAX_OPTION_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "option_id")
    private Long optionId;

    @Column(name = "question_id", nullable = false, updatable = false)
    private Long questionId;

    @Column(name = "option_no", nullable = false, updatable = false)
    private int optionNo;

    @Column(name = "option_text", nullable = false, updatable = false, length = MAX_OPTION_LENGTH)
    private String optionText;

    public QuizOption(Long questionId, int optionNo, String optionText) {
        this.questionId = Objects.requireNonNull(questionId, "questionId");
        this.optionNo = optionNo;
        this.optionText = Objects.requireNonNull(optionText, "optionText");
    }
}
