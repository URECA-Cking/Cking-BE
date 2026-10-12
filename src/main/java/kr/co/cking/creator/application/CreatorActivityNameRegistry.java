package kr.co.cking.creator.application;

import java.time.LocalDateTime;
import java.util.Optional;
import kr.co.cking.creator.domain.CreatorActivityName;
import kr.co.cking.creator.domain.CreatorActivityNameReservation;
import kr.co.cking.creator.repository.CreatorActivityNameReservationRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 현재 활동명과 14일 복귀 예약을 함께 판정하는 Creator 전용 namespace registry다. */
@Component
@RequiredArgsConstructor
class CreatorActivityNameRegistry {

    private final CreatorRepository creatorRepository;
    private final CreatorActivityNameReservationRepository reservationRepository;

    boolean isTaken(CreatorActivityName activityName, Long creatorId, LocalDateTime now) {
        if (creatorRepository.existsByActivityNameNormalized(activityName.normalized())) {
            return true;
        }
        return findActive(activityName.normalized(), now)
                .map(reservation -> reservation.blocks(creatorId, now))
                .orElse(false);
    }

    Optional<CreatorActivityNameReservation> findOwnedActive(
            CreatorActivityName activityName, Long creatorId, LocalDateTime now) {
        return findActive(activityName.normalized(), now)
                .filter(reservation -> reservation.isOwnedBy(creatorId, now));
    }

    void clear(CreatorActivityName activityName) {
        reservationRepository.findByActivityNameNormalized(activityName.normalized()).ifPresent(reservationRepository::delete);
    }

    void reserveReleased(CreatorActivityName activityName, Long creatorId, LocalDateTime now) {
        reservationRepository.findByActivityNameNormalized(activityName.normalized()).ifPresentOrElse(
                reservation -> reservation.renew(activityName, creatorId, now),
                () -> reservationRepository.save(CreatorActivityNameReservation.reserve(activityName, creatorId, now)));
    }

    private Optional<CreatorActivityNameReservation> findActive(String normalized, LocalDateTime now) {
        return reservationRepository.findByActivityNameNormalized(normalized)
                .filter(reservation -> {
                    if (reservation.isActiveAt(now)) {
                        return true;
                    }
                    reservationRepository.delete(reservation);
                    return false;
                });
    }
}
