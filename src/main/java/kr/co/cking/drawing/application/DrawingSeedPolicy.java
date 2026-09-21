package kr.co.cking.drawing.application;

import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.drawing.domain.seed.DrawingSeedGenerator;
import org.springframework.stereotype.Component;

@Component
public class DrawingSeedPolicy {

    private static final int MAX_GENERATION_ATTEMPTS = 100;

    private final DrawingSeedGenerator seedGenerator;

    public DrawingSeedPolicy(DrawingSeedGenerator seedGenerator) {
        if (seedGenerator == null) {
            throw new IllegalArgumentException("DrawingSeedGenerator는 필수입니다.");
        }
        this.seedGenerator = seedGenerator;
    }

    public DrawingSeed createForInitial() {
        return generateRequired();
    }

    public DrawingSeed createForRedraw(DrawingSeed previousSeed) {
        return generateDifferentFrom(previousSeed);
    }

    /** 독립 재실행은 원본 Drawing과 다른 Seed를 사용하되 Seed 행을 새로 만들지 않는다. */
    public DrawingSeed createForVerification(DrawingSeed originalSeed) {
        return generateDifferentFrom(originalSeed);
    }

    private DrawingSeed generateDifferentFrom(DrawingSeed previousSeed) {
        if (previousSeed == null) {
            throw new IllegalArgumentException("이전 Drawing의 Seed는 필수입니다.");
        }

        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            DrawingSeed generatedSeed = generateRequired();
            if (!previousSeed.equals(generatedSeed)) {
                return generatedSeed;
            }
        }
        throw new IllegalStateException("이전 Drawing과 다른 Seed를 생성하지 못했습니다.");
    }

    public DrawingSeed reuseForRetry(String storedSeedValue) {
        // Retry 입력 변경 방지를 위한 저장 Seed의 검증 후 재사용.
        return DrawingSeed.from(storedSeedValue);
    }

    private DrawingSeed generateRequired() {
        DrawingSeed generatedSeed = seedGenerator.generate();
        if (generatedSeed == null) {
            throw new IllegalStateException("생성된 Seed는 null일 수 없습니다.");
        }
        return generatedSeed;
    }
}
