package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CreatorSpaceTemplateRepository extends JpaRepository<CreatorSpaceTemplate, Long> {

    Optional<CreatorSpaceTemplate> findByActiveMarker(Integer activeMarker);

    Page<CreatorSpaceTemplate> findAllByOrderByCreatedAtDescTemplateIdDesc(Pageable pageable);
}
