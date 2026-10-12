package kr.co.cking.quiz.application.dto;

import kr.co.cking.quiz.domain.QuizRevisionFingerprint;

import java.util.List;

/**
 * 저장된 문항 전체(정답·해설·근거 포함). Creator 검토·무결성 검증·서버 채점에 사용하며 공개 Quiz 조회 응답으로 내보내지 않는다.
 *
 * @param questionNo         0-based 문항 순서
 * @param correctOptionIndex 0-based 정답 보기 순서
 */
public record QuizQuestionContent(
        int questionNo,
        String question,
        List<String> options,
        int correctOptionIndex,
        String explanation,
        String sourceEvidence
) {

    public QuizRevisionFingerprint.Question toFingerprintQuestion() {
        return new QuizRevisionFingerprint.Question(question, options, correctOptionIndex, explanation, sourceEvidence);
    }
}
