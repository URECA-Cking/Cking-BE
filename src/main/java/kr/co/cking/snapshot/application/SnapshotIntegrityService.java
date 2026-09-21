package kr.co.cking.snapshot.application;

import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import kr.co.cking.snapshot.domain.DrawSnapshotCandidate;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import kr.co.cking.snapshot.repository.DrawSnapshotCandidateRepository;
import kr.co.cking.snapshot.repository.DrawSnapshotRepository;
import kr.co.cking.snapshot.repository.DrawSnapshotPrizeRepository;
import kr.co.cking.snapshot.domain.PrizeValue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class SnapshotIntegrityService {

    private final DrawSnapshotRepository snapshotRepository;
    private final DrawSnapshotCandidateRepository candidateRepository;
    private final SnapshotHashGenerator hashGenerator;
    private final SnapshotHashV2Generator hashV2Generator;
    private final DrawSnapshotPrizeRepository prizeRepository;

    public SnapshotIntegrityService(DrawSnapshotRepository snapshotRepository,
            DrawSnapshotCandidateRepository candidateRepository, SnapshotHashGenerator hashGenerator) {
        this(snapshotRepository, candidateRepository, hashGenerator, new SnapshotHashV2Generator(), null);
    }

    @Transactional(readOnly = true)
    public VerifiedSnapshot verifyForDrawing(Long eventId) {
        if (eventId == null || eventId <= 0) {
            throw new IllegalArgumentException("eventId는 양수여야 합니다.");
        }

        DrawSnapshot snapshot = snapshotRepository.findByEventId(eventId)
                .orElseThrow(() -> new BusinessException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));
        return verify(snapshot);
    }

    /** 현재 Event가 아니라 완료된 Drawing이 참조한 당시 Snapshot 자체를 검증한다. */
    @Transactional(readOnly = true)
    public VerifiedSnapshot verifyForReplay(Long snapshotId) {
        if (snapshotId == null || snapshotId <= 0) {
            throw new IllegalArgumentException("snapshotId는 양수여야 합니다.");
        }

        DrawSnapshot snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new BusinessException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));
        return verify(snapshot);
    }

    private VerifiedSnapshot verify(DrawSnapshot snapshot) {
        List<CandidateValue> candidates = candidateRepository
                .findAllBySnapshot_IdOrderByMemberIdAsc(snapshot.getId())
                .stream()
                .map(this::toCandidateValue)
                .toList();

        List<PrizeValue> prizes = prizeRepository == null ? List.of()
                : prizeRepository.findAllBySnapshot_IdOrderByPriorityAscPrizeKeyAsc(snapshot.getId())
                        .stream().map(prize -> prize.toValue()).toList();
        SnapshotHash recalculated = prizes.isEmpty()
                ? hashGenerator.generate(new SnapshotHashInput(snapshot.getEventId(), snapshot.getWinnerCount(),
                        snapshot.getDrawMethod(), snapshot.getAlgorithmVersion(), candidates))
                : hashV2Generator.generate(new SnapshotHashV2Input(snapshot.getEventId(), snapshot.getWinnerCount(),
                        snapshot.getDrawMethod(), snapshot.getAlgorithmVersion(), snapshot.getPrizeAlgorithmVersion(),
                        candidates, prizes));

        if (!hasValidAggregateTotals(snapshot, candidates)
                || !snapshot.getSnapshotHash().equals(recalculated.value())) {
            throw new BusinessException(SnapshotErrorCode.SNAPSHOT_HASH_MISMATCH);
        }

        return VerifiedSnapshot.from(snapshot, candidates, prizes);
    }

    private CandidateValue toCandidateValue(DrawSnapshotCandidate candidate) {
        return new CandidateValue(candidate.getMemberId(), candidate.getTicketCount());
    }

    private boolean hasValidAggregateTotals(DrawSnapshot snapshot, List<CandidateValue> candidates) {
        long totalTicketCount = candidates.stream()
                .mapToLong(CandidateValue::ticketCount)
                .sum();
        return snapshot.getCandidateCount() == candidates.size()
                && snapshot.getTotalTicketCount() == totalTicketCount;
    }
}
