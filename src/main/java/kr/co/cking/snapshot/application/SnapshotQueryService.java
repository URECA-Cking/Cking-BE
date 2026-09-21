package kr.co.cking.snapshot.application;

import java.util.Comparator;
import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import kr.co.cking.snapshot.repository.DrawSnapshotCandidateRepository;
import kr.co.cking.snapshot.repository.DrawSnapshotRepository;
import kr.co.cking.snapshot.repository.DrawSnapshotPrizeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
@Transactional(readOnly = true)
public class SnapshotQueryService {

    private final MemberQueryService memberQueryService;
    private final DrawSnapshotRepository snapshotRepository;
    private final DrawSnapshotCandidateRepository candidateRepository;
    private final DrawSnapshotPrizeRepository prizeRepository;

    public SnapshotQueryService(MemberQueryService memberQueryService, DrawSnapshotRepository snapshotRepository,
            DrawSnapshotCandidateRepository candidateRepository) {
        this(memberQueryService, snapshotRepository, candidateRepository, null);
    }

    public SnapshotQueryResult getOfficialSnapshot(Long eventId, Long userId) {
        memberQueryService.validateAdmin(userId);

        DrawSnapshot snapshot = snapshotRepository.findByEventId(eventId)
                .orElseThrow(() -> new BusinessException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));
        List<SnapshotCandidateResult> candidates = candidateRepository
                .findAllBySnapshot_IdOrderByMemberIdAsc(snapshot.getId())
                .stream()
                .map(SnapshotCandidateResult::from)
                .sorted(Comparator.comparing(SnapshotCandidateResult::userId))
                .toList();

        List<SnapshotPrizeResult> prizes = prizeRepository == null ? List.of() : prizeRepository
                .findAllBySnapshot_IdOrderByPriorityAscPrizeKeyAsc(snapshot.getId()).stream()
                .map(SnapshotPrizeResult::from).toList();
        return SnapshotQueryResult.from(snapshot, candidates, prizes);
    }
}
