package kr.co.cking.quiz.grounding;

/**
 * 영상에서 확인한 사실 하나와 그 근거.
 *
 * @param timestampStart 근거 구간 시작. {@code MM:SS} 또는 {@code MM:SS.s}. 특정할 수 없으면 끝과 함께 null
 * @param timestampEnd   근거 구간 끝. 시작보다 앞설 수 없다
 */
public record GroundingFact(
        String fact,
        EvidenceType evidenceType,
        String evidence,
        String timestampStart,
        String timestampEnd
) {
}
