package kr.co.cking.quiz.application.dto;

/** 새로 저장한 Quiz와 첫 revision. */
public record QuizDraft(Long quizId, Long revisionId) {
}
