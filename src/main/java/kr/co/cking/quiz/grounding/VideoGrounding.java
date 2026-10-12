package kr.co.cking.quiz.grounding;

import java.util.List;

/**
 * 검증된 영상 Grounding 결과. contentText는 Provider가 준 원문 그대로이며 Quiz 생성 입력이 된다.
 * facts는 호출자에게만 돌려주고 저장하지 않는다.
 *
 * @param promptVersion 실행에 쓴 Grounding prompt 버전. Provider 출력이 아니라 실행 설정에서 정한다
 * @param model         실행에 쓴 모델. Provider 출력이 아니라 실행 설정에서 정한다
 */
public record VideoGrounding(String contentText, List<GroundingFact> facts, String promptVersion, String model) {

    public VideoGrounding {
        facts = List.copyOf(facts);
    }
}
