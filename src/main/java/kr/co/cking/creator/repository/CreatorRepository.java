package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.Creator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CreatorRepository extends JpaRepository<Creator, Long> {

    Optional<Creator> findByMemberId(Long memberId);

    boolean existsByMemberId(Long memberId);

    List<Creator> findByCreatorIdIn(Collection<Long> creatorIds);
}
