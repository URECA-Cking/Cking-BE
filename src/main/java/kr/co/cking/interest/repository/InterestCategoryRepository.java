package kr.co.cking.interest.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import kr.co.cking.interest.domain.InterestCategory;
import kr.co.cking.interest.domain.InterestCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterestCategoryRepository extends JpaRepository<InterestCategory, InterestCategoryId> {

    @Query("select c from InterestCategory c where c.id.taxonomyVersion = :version and c.active = true "
            + "order by c.displayOrder")
    List<InterestCategory> findActiveByTaxonomyVersion(@Param("version") String version);

    /** 해시 계산 대상이다. 활성 여부와 무관하게 버전의 모든 행을 노출 순서대로 읽는다. */
    @Query("select c from InterestCategory c where c.id.taxonomyVersion = :version order by c.displayOrder")
    List<InterestCategory> findAllByTaxonomyVersion(@Param("version") String version);

    /** 같은 분야에 대한 명령(추천 후보 적재)을 직렬화하려고 분야 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InterestCategory c where c.id.taxonomyVersion = :version and c.id.interestCode = :code")
    Optional<InterestCategory> findByIdForUpdate(@Param("version") String version, @Param("code") String code);
}
