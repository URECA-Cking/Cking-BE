package kr.co.cking.drawing.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.DrawSeed;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.drawing.repository.DrawSeedRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Drawing 생명주기에 따른 Seed 생성, 저장, 재사용의 단일 진입점. */
@Service
@RequiredArgsConstructor
public class DrawingSeedService {

    private final DrawSeedRepository drawSeedRepository;
    private final DrawingSeedPolicy drawingSeedPolicy;

    // Drawing 실행 Transaction이 없을 때 고아 Seed 저장을 차단하기 위한 호출자 Transaction 강제.
    @Transactional(propagation = Propagation.MANDATORY)
    public PersistedDrawingSeed createForInitial() {
        return persist(drawingSeedPolicy.createForInitial());
    }

    @Transactional(readOnly = true)
    public PersistedDrawingSeed reuseForRetry(Long seedId) {
        return load(seedId);
    }

    // REDRAW 생성과 신규 Seed 저장의 단일 Transaction 보장.
    @Transactional(propagation = Propagation.MANDATORY)
    public PersistedDrawingSeed createForRedraw(Long previousSeedId) {
        PersistedDrawingSeed previous = load(previousSeedId);
        DrawingSeed redrawSeed = drawingSeedPolicy.createForRedraw(previous.seed());
        return persist(redrawSeed);
    }

    private PersistedDrawingSeed persist(DrawingSeed seed) {
        DrawSeed saved = drawSeedRepository.save(DrawSeed.create(seed));
        return new PersistedDrawingSeed(saved.getId(), saved.restore());
    }

    private PersistedDrawingSeed load(Long seedId) {
        if (seedId == null || seedId <= 0) {
            throw new IllegalArgumentException("seedId는 양수여야 합니다.");
        }

        DrawSeed stored = drawSeedRepository.findById(seedId)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.DRAWING_SEED_NOT_FOUND));
        // Retry에서 신규 Seed 생성을 우회하고 저장된 정규 Seed만 재사용.
        DrawingSeed reused = drawingSeedPolicy.reuseForRetry(stored.restore().value());
        return new PersistedDrawingSeed(stored.getId(), reused);
    }
}
