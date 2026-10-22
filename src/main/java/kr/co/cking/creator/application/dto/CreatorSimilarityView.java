package kr.co.cking.creator.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 공개 조회에 노출할 현재 활성 추천 세대와 후보 목록이다. */
public record CreatorSimilarityView(
        Long creatorId,
        String method,
        String modelVersion,
        String inputHash,
        Instant generatedAt,
        List<Candidate> candidates
) {
    public static CreatorSimilarityView empty(Long creatorId) {
        return new CreatorSimilarityView(creatorId, null, null, null, null, List.of());
    }

    public record Candidate(
            Long similarCreatorId,
            String name,
            BigDecimal score,
            int rank
    ) {
    }
}
