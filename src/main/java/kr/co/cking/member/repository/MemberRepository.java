package kr.co.cking.member.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    List<Member> findByMemberIdIn(Collection<Long> memberIds);

    Optional<Member> findByEmail(String email);

    /** 같은 회원에 대한 명령(예: 관심 분야 전체 교체)을 직렬화하려고 회원 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.memberId = :memberId")
    Optional<Member> findByIdForUpdate(@Param("memberId") Long memberId);
}
