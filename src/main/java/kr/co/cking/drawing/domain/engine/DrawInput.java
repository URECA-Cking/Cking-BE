package kr.co.cking.drawing.domain.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;

public record DrawInput(
        Long snapshotId,
        DrawingSeed seed,
        String algorithmVersion,
        int winnerCount,
        List<CandidateValue> candidates,
        Set<Long> excludedMemberIds
) {

    public DrawInput {
        validateRequiredValues(snapshotId, seed, algorithmVersion, winnerCount, candidates,
                excludedMemberIds);
        validateCandidates(candidates);
        validateExcludedMemberIds(excludedMemberIds);

        // 입력 Candidate 순서와 무관한 결정성 보장을 위한 memberId 오름차순 정규화.
        candidates = candidates.stream()
                .sorted(CandidateValue.BY_MEMBER_ID)
                .toList();
        excludedMemberIds = Set.copyOf(excludedMemberIds);
    }

    private static void validateRequiredValues(
            Long snapshotId,
            DrawingSeed seed,
            String algorithmVersion,
            int winnerCount,
            List<CandidateValue> candidates,
            Set<Long> excludedMemberIds
    ) {
        if (snapshotId == null || snapshotId <= 0) {
            throw new IllegalArgumentException("snapshotId는 양수여야 합니다.");
        }
        if (seed == null) {
            throw new IllegalArgumentException("Seed는 필수입니다.");
        }
        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            throw new IllegalArgumentException("algorithmVersion은 필수입니다.");
        }
        if (winnerCount <= 0) {
            throw new IllegalArgumentException("winnerCount는 양수여야 합니다.");
        }
        if (candidates == null) {
            throw new IllegalArgumentException("candidates는 필수입니다.");
        }
        if (excludedMemberIds == null) {
            throw new IllegalArgumentException("excludedMemberIds는 필수입니다.");
        }
    }

    private static void validateCandidates(List<CandidateValue> candidates) {
        Set<Long> memberIds = new HashSet<>();
        for (CandidateValue candidate : candidates) {
            if (candidate == null) {
                throw new IllegalArgumentException("candidate는 null일 수 없습니다.");
            }
            if (!memberIds.add(candidate.memberId())) {
                throw new IllegalArgumentException("Candidate의 memberId는 중복될 수 없습니다.");
            }
        }
    }

    private static void validateExcludedMemberIds(Set<Long> excludedMemberIds) {
        for (Long memberId : excludedMemberIds) {
            if (memberId == null || memberId <= 0) {
                throw new IllegalArgumentException("제외 대상 memberId는 양수여야 합니다.");
            }
        }
    }
}
