package kr.co.cking.creator.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CreatorSpaceTemplateRepository extends JpaRepository<CreatorSpaceTemplate, Long> {

    Optional<CreatorSpaceTemplate> findByActiveMarker(Integer activeMarker);

    Page<CreatorSpaceTemplate> findAllByOrderByCreatedAtDescTemplateIdDesc(Pageable pageable);

    /**
     * 활성화 처리 전용 잠금 조회다. MySQL REPEATABLE READ에서는 트랜잭션의 첫 SELECT가
     * 스냅샷을 고정하고, GET_LOCK처럼 테이블을 읽지 않는 호출은 스냅샷에 영향을 주지 않는다.
     * 그래서 advisory lock 획득보다 먼저 실행된 다른 SELECT(예: 관리자 검증)가 이미 있으면
     * 일반 조회는 advisory lock 안에서 다시 해도 그 오래된 스냅샷을 그대로 쓸 수 있다.
     * FOR UPDATE(PESSIMISTIC_WRITE)는 스냅샷과 무관하게 항상 최신 커밋 데이터를 읽으므로,
     * 활성화 판단에 쓰는 조회는 반드시 이 메서드를 쓴다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CreatorSpaceTemplate t where t.templateId = :templateId")
    Optional<CreatorSpaceTemplate> findByIdForActivation(@Param("templateId") Long templateId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CreatorSpaceTemplate t where t.activeMarker = :activeMarker")
    Optional<CreatorSpaceTemplate> findByActiveMarkerForActivation(@Param("activeMarker") Integer activeMarker);
}
