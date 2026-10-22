package kr.co.cking.drawing.domain.engine;

/**
 * 외부 저장소에 의존하지 않는 순수 추첨 엔진 계약.
 */
public interface DrawingEngine {

    DrawOutput draw(DrawInput input);
}
