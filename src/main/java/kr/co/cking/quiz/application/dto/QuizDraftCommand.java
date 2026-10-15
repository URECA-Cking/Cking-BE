package kr.co.cking.quiz.application.dto;

import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationInput;

/**
 * 검증된 AI 생성 결과를 새 Quiz로 저장하는 내부 명령. 외부 API로 받지 않는다.
 *
 * @param sourceVideoId YouTube videoId(11자)
 * @param title         Creator가 지정한 제목
 * @param input         생성에 쓴 원본 텍스트와 문항·보기 수. 원본 텍스트는 Quiz에 그대로 저장한다
 * @param generated     {@code input}으로 생성한 결과
 */
public record QuizDraftCommand(
        Long creatorId,
        String sourceVideoId,
        String title,
        QuizGenerationInput input,
        GeneratedQuiz generated
) {
}
