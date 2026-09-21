package kr.co.cking.drawing.domain.engine;

import java.util.Map;

/** 후보 선정 알고리즘 버전에 맞는 순수 엔진을 선택하는 라우터다. */
public final class ResolvingDrawingEngine implements DrawingEngine {
    private final Map<DrawingAlgorithmVersion, DrawingEngine> engines;

    public ResolvingDrawingEngine(Map<DrawingAlgorithmVersion, DrawingEngine> engines) {
        if (engines == null || engines.isEmpty() || engines.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("후보 선정 엔진은 하나 이상 필요합니다.");
        }
        this.engines = Map.copyOf(engines);
    }

    @Override
    public DrawOutput draw(DrawInput input) {
        if (input == null) {
            throw new IllegalArgumentException("DrawInput은 필수입니다.");
        }
        DrawingAlgorithmVersion version = DrawingAlgorithmVersion.from(input.algorithmVersion());
        DrawingEngine engine = engines.get(version);
        if (engine == null) {
            throw new IllegalArgumentException("지원하지 않는 후보 선정 알고리즘입니다: " + version);
        }
        return engine.draw(input);
    }
}
