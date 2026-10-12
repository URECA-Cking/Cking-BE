package kr.co.cking.quiz.application.dto;

import java.util.UUID;

/**
 * YouTube 영상 Quiz 생성 요청.
 *
 * @param questionCount 3 또는 5. null이면 3이다
 */
public record QuizGenerationCommand(UUID requestId, String youtubeUrl, String title, Integer questionCount) {
}
