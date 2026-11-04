package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.Collection;

public interface CreatorApplicationRepository extends JpaRepository<CreatorApplication, Long> {

    Optional<CreatorApplication> findFirstByMemberIdAndStatusInOrderByIdDesc(
            Long memberId, Collection<CreatorApplicationStatus> statuses
    );

    Page<CreatorApplication> findByMemberIdOrderByRequestedAtDescIdDesc(Long memberId, Pageable pageable);

    Page<CreatorApplication> findByStatusOrderByRequestedAtAscIdAsc(
            CreatorApplicationStatus status,
            Pageable pageable
    );

    Page<CreatorApplication> findAllByOrderByRequestedAtAscIdAsc(Pageable pageable);
}
