package kr.co.cking.creator.application.dto;

import java.math.BigDecimal;
import java.util.List;

/** Cking-LLM이 전달한 한 크리에이터의 완결된 유사 후보 묶음이다. */
public record CreatorSimilarityResultCommand(
        Long creatorId,
        List<Candidate> candidates
) {
    public record Candidate(
            Long creatorId,
            Long similarCreatorId,
            BigDecimal score,
            Integer rank,
            String method,
            String modelVersion,
            String inputHash
    ) {
    }
}
