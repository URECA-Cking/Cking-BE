package kr.co.cking.quiz.repository;

import java.time.Instant;

/** 공개 목록에 필요한 Quiz 값. 원본 텍스트(content_text)는 담지 않는다. */
public record PublishedQuizSummary(Long quizId, Long publishedRevisionId, Instant publishedAt) {
}
