package kr.co.cking.member.repository;

import kr.co.cking.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    List<Member> findByMemberIdIn(Collection<Long> memberIds);

    Optional<Member> findByEmail(String email);
}
