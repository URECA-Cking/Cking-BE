package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.ContentTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Same database transaction as the Space write; no trigger/SUPER privilege is needed. */
@Service
@RequiredArgsConstructor
public class CreatorContentOutbox {
    private final ContentTagRepository repo;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(CreatorSpace space) {
        repo.update("""
            INSERT INTO creator_content_job(creator_id,generation,committed_at,next_retry_at)
                VALUES(?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE
                status=IF(generation<VALUES(generation),'PENDING',status),
                lease_token=IF(generation<VALUES(generation),NULL,lease_token),
                lease_until=IF(generation<VALUES(generation),NULL,lease_until),
                attempt_count=IF(generation<VALUES(generation),0,attempt_count),
                committed_at=IF(generation<VALUES(generation),UTC_TIMESTAMP(6),committed_at),
                claimed_at=IF(generation<VALUES(generation),NULL,claimed_at),
                completed_at=IF(generation<VALUES(generation),NULL,completed_at),
                next_retry_at=IF(generation<VALUES(generation),UTC_TIMESTAMP(6),next_retry_at),
                last_summary=IF(generation<VALUES(generation),NULL,last_summary),
                generation=GREATEST(generation,VALUES(generation))
            """,space.getCreatorId(),space.getContentRevision());
    }
}
