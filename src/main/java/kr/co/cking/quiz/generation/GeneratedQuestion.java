package kr.co.cking.quiz.generation;

import java.util.List;

/** 검수 전 AI 생성 객관식 문항이다. */
public record GeneratedQuestion(
        String question,
        List<String> options,
        int correctOptionIndex,
        String explanation,
        String sourceEvidence
) {
}
