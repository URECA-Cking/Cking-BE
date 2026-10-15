package kr.co.cking.member.application;

import kr.co.cking.member.domain.MemberNickname;
import kr.co.cking.member.domain.MemberNicknameReservation;
import kr.co.cking.member.repository.MemberNicknameReservationRepository;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/** 현재 사용 중인 닉네임과 이전 닉네임 예약을 함께 판정한다. */
@Component
@RequiredArgsConstructor
class MemberNicknameRegistry {

    private final MemberRepository memberRepository;
    private final MemberNicknameReservationRepository reservationRepository;

    /** 현재 회원이 쓰는 닉네임을 먼저 확인한 뒤 예약을 확인해 해제·선점 사이의 틈을 막는다. */
    boolean isTaken(MemberNickname nickname, Long memberId, LocalDateTime now) {
        if (memberRepository.existsByNicknameNormalized(nickname.normalized())) {
            return true;
        }
        return findActive(nickname.normalized(), now)
                .map(reservation -> reservation.blocks(memberId, now))
                .orElse(false);
    }

    /** 바로 이전 닉네임이고, 아직 본인 예약이면 14일 제한 없이 되돌릴 수 있다. */
    Optional<MemberNicknameReservation> findOwnedActive(MemberNickname nickname, Long memberId, LocalDateTime now) {
        return findActive(nickname.normalized(), now)
                .filter(reservation -> reservation.isOwnedBy(memberId, now));
    }

    void clear(MemberNickname nickname) {
        reservationRepository.findByNicknameNormalized(nickname.normalized()).ifPresent(reservationRepository::delete);
    }

    void reserveReleased(MemberNickname nickname, Long memberId, LocalDateTime now) {
        reservationRepository.findByNicknameNormalized(nickname.normalized()).ifPresentOrElse(
                reservation -> reservation.renew(nickname, memberId, now),
                () -> reservationRepository.save(MemberNicknameReservation.reserve(nickname, memberId, now))
        );
    }

    private Optional<MemberNicknameReservation> findActive(String normalized, LocalDateTime now) {
        return reservationRepository.findByNicknameNormalized(normalized)
                .filter(reservation -> {
                    if (reservation.isActiveAt(now)) {
                        return true;
                    }
                    reservationRepository.delete(reservation);
                    return false;
                });
    }
}
