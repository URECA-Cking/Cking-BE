package kr.co.cking.quiz.repository;

/** 평가 Job과 그 Job이 속한 Quiz·revision·평가 묶음의 ID. 잠금 순서를 정하려고 Job을 잠그기 전에 읽는다. */
public record QuizJudgeJobKeys(Long jobId, Long quizId, Long revisionId, Long seriesId) {
}
