package kr.co.cking.member.repository;

import kr.co.cking.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface MemberRepository extends JpaRepository<Member, Long> {

    List<Member> findByMemberIdIn(Collection<Long> memberIds);
}
