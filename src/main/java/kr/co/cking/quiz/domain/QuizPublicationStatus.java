package kr.co.cking.quiz.domain;

/** Quiz의 공개 상태. revision의 검토 상태({@link QuizReviewStatus})와 별개다. */
public enum QuizPublicationStatus {
    NOT_PUBLISHED,
    PUBLISHED,
    UNPUBLISHED
}
