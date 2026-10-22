package kr.co.cking.creator.application.dto;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Cking-LLM이 전달한 한 크리에이터의 완결된 유사 후보 묶음이다. */
public record CreatorSimilarityResultCommand(
        Long applicationSequence,
        Long creatorId,
        String method,
        String modelVersion,
        String inputHash,
        List<Candidate> candidates,
        JsonNode eligibility
) {

    /** 자격 근거 없이 적재하는 구 호출용이다. 근거 없는 세대는 조회에서 제외된다. */
    public CreatorSimilarityResultCommand(
            Long applicationSequence, Long creatorId, String method, String modelVersion, String inputHash,
            List<Candidate> candidates) {
        this(applicationSequence, creatorId, method, modelVersion, inputHash, candidates, null);
    }

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
