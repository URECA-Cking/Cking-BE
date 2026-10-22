package kr.co.cking.drawing.domain.prize;

import java.util.Map;

/** 상품 알고리즘 버전에 맞는 순수 엔진을 선택하는 라우터다. */
public final class ResolvingPrizeAllocationEngine implements PrizeAllocationEngine {
    private final Map<PrizeAllocationAlgorithmVersion, PrizeAllocationEngine> engines;

    public ResolvingPrizeAllocationEngine(Map<PrizeAllocationAlgorithmVersion, PrizeAllocationEngine> engines) {
        if (engines == null || engines.isEmpty() || engines.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("상품 배정 엔진은 하나 이상 필요합니다.");
        }
        this.engines = Map.copyOf(engines);
    }

    @Override
    public PrizeAllocationOutput allocate(PrizeAllocationInput input) {
        if (input == null) {
            throw new IllegalArgumentException("PrizeAllocationInput은 필수입니다.");
        }
        PrizeAllocationAlgorithmVersion version = PrizeAllocationAlgorithmVersion.from(input.algorithmVersion());
        PrizeAllocationEngine engine = engines.get(version);
        if (engine == null) {
            throw new IllegalArgumentException("지원하지 않는 상품 배정 알고리즘입니다: " + version);
        }
        return engine.allocate(input);
    }
}
