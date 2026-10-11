package kr.co.cking.member.repository;

import kr.co.cking.member.domain.MemberNicknameReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberNicknameReservationRepository extends JpaRepository<MemberNicknameReservation, Long> {

    Optional<MemberNicknameReservation> findByNicknameNormalized(String nicknameNormalized);
}
