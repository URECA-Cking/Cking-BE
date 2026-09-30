package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.CreatorSpace;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CreatorSpaceRepository extends JpaRepository<CreatorSpace, Long> {

    Optional<CreatorSpace> findByCreatorId(Long creatorId);

    /** 공유 URL의 slug로 Creator Space를 조회한다. */
    Optional<CreatorSpace> findBySlug(String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CreatorSpace s where s.creatorId = :creatorId")
    Optional<CreatorSpace> findByCreatorIdForUpdate(@Param("creatorId") Long creatorId);

    boolean existsBySlug(String slug);

    /**
     * 공개 Creator 목록용 조회. Creator 이름 오름차순, 같으면 creatorId 오름차순으로 정렬해 페이지를 넘겨도
     * 항목이 누락되거나 중복되지 않게 한다. {@code namePattern}은 LIKE 패턴이며 이스케이프 문자는 {@code !}다.
     */
    @Query(value = """
            select s from CreatorSpace s, Creator c
            where c.creatorId = s.creatorId and c.name like :namePattern escape '!'
            order by c.name asc, s.creatorId asc
            """,
            countQuery = """
                    select count(s) from CreatorSpace s, Creator c
                    where c.creatorId = s.creatorId and c.name like :namePattern escape '!'
                    """)
    Page<CreatorSpace> findAllByCreatorNamePattern(@Param("namePattern") String namePattern, Pageable pageable);
}
