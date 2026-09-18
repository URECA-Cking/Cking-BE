package kr.co.cking.snapshot.application;

import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import kr.co.cking.snapshot.domain.PrizeValue;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import kr.co.cking.snapshot.repository.DrawSnapshotRepository;
import kr.co.cking.snapshot.repository.SnapshotEventSource;
import kr.co.cking.snapshot.repository.SnapshotSourceQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class OfficialSnapshotService {

    static final String ALGORITHM_VERSION = "WEIGHTED_V1";

    private final DrawSnapshotRepository snapshotRepository;
    private final SnapshotSourceQueryRepository sourceQueryRepository;
    private final SnapshotHashGenerator hashGenerator;
    private final SnapshotHashV2Generator hashV2Generator;

    public OfficialSnapshotService(DrawSnapshotRepository snapshotRepository,
            SnapshotSourceQueryRepository sourceQueryRepository, SnapshotHashGenerator hashGenerator) {
        this(snapshotRepository, sourceQueryRepository, hashGenerator, new SnapshotHashV2Generator());
    }

    @Transactional
    public OfficialSnapshotResult createIfAbsent(Long eventId) {
        if (eventId == null || eventId <= 0) {
            throw new IllegalArgumentException("eventId는 양수여야 합니다.");
        }

        // Event 행을 먼저 잠가 동일 Event의 Snapshot 생성을 직렬화한다.
        // MySQL REPEATABLE READ에서 잠금 전 일반 조회를 하면 오래된 read view를 재사용할 수 있다.
        SnapshotEventSource event = sourceQueryRepository.findEventForUpdate(eventId)
                .orElseThrow(() -> new BusinessException(SnapshotErrorCode.EVENT_NOT_FOUND));

        OfficialSnapshotResult existing = snapshotRepository.findByEventId(eventId)
                .map(OfficialSnapshotResult::from)
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        if (event.status() != EventStatus.CLOSED) {
            throw new BusinessException(SnapshotErrorCode.EVENT_NOT_CLOSED);
        }

        List<CandidateValue> candidates = sourceQueryRepository.findCandidates(eventId);
        List<PrizeValue> prizes = java.util.Optional.ofNullable(sourceQueryRepository.findPrizes(eventId))
                .orElse(List.of());
        SnapshotHash hash = prizes.isEmpty()
                ? hashGenerator.generate(new SnapshotHashInput(event.eventId(), event.winnerCount(),
                        event.drawMethod(), ALGORITHM_VERSION, candidates))
                : hashV2Generator.generate(new SnapshotHashV2Input(event.eventId(), event.winnerCount(),
                        event.drawMethod(), ALGORITHM_VERSION, "PRIZE_WEIGHTED_V1", candidates, prizes));

        DrawSnapshot snapshot = DrawSnapshot.create(
                event.eventId(),
                event.winnerCount(),
                event.drawMethod(),
                ALGORITHM_VERSION,
                "PRIZE_WEIGHTED_V1",
                hash.value(),
                candidates.stream().sorted(CandidateValue.BY_MEMBER_ID).toList(),
                prizes
        );
        return OfficialSnapshotResult.from(snapshotRepository.saveAndFlush(snapshot));
    }
}
