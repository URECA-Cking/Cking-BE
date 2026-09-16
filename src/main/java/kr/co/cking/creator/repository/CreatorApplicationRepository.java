package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CreatorApplicationRepository extends JpaRepository<CreatorApplication, Long> {

    Optional<CreatorApplication> findFirstByMemberIdAndStatusOrderByIdDesc(
            Long memberId,
            CreatorApplicationStatus status
    );
}
