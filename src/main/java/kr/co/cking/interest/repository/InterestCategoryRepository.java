package kr.co.cking.interest.repository;

import java.util.List;
import kr.co.cking.interest.domain.InterestCategory;
import kr.co.cking.interest.domain.InterestCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterestCategoryRepository extends JpaRepository<InterestCategory, InterestCategoryId> {

    @Query("select c from InterestCategory c where c.id.taxonomyVersion = :version and c.active = true "
            + "order by c.displayOrder")
    List<InterestCategory> findActiveByTaxonomyVersion(@Param("version") String version);

    /** 해시 계산 대상이다. 활성 여부와 무관하게 버전의 모든 행을 노출 순서대로 읽는다. */
    @Query("select c from InterestCategory c where c.id.taxonomyVersion = :version order by c.displayOrder")
    List<InterestCategory> findAllByTaxonomyVersion(@Param("version") String version);
}
