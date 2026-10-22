package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.CreatorSpaceSlugReservation;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.creator.repository.CreatorSpaceSlugReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * slug 사용 가능 여부와 이전 slug 예약을 한곳에서 판단한다(이슈 #301). 커스텀 slug 변경과
 * 승인 시 자동 slug 생성이 같은 기준을 쓰게 하기 위해서다. 호출자의 트랜잭션에 참여한다.
 */
@Component
@RequiredArgsConstructor
class CreatorSpaceSlugRegistry {

    private final CreatorSpaceRepository spaceRepository;
    private final CreatorSpaceSlugReservationRepository reservationRepository;

    /**
     * 다른 Space가 쓰고 있거나, 다른 Creator가 예약 기간 안에 잡아 둔 slug면 true다.
     *
     * <p>Space 사용 여부를 먼저, 예약을 나중에 조회해야 한다. slug를 버리는 쪽은 "Space slug 변경"과
     * "예약 추가"를 한 트랜잭션으로 커밋하므로, 첫 조회가 커밋 전이면 아직 사용 중으로 보이고 커밋 후면
     * 두 번째 조회에서 예약이 보인다. 순서를 바꾸면 READ COMMITTED에서 둘 다 놓칠 수 있다.
     * 최종 안전망은 creator_space.slug UNIQUE 제약이다.
     */
    boolean isTaken(String slug, Long creatorId, LocalDateTime now) {
        if (spaceRepository.existsBySlug(slug)) {
            return true;
        }
        return reservationRepository.findBySlug(slug)
                .map(reservation -> reservation.blocks(creatorId, now))
                .orElse(false);
    }

    /** Creator 본인이 버린 뒤 아직 예약 기간 안인 slug면 true다. 이 slug로 돌아가는 것은 되돌리기다. */
    boolean isReservedBy(String slug, Long creatorId, LocalDateTime now) {
        return reservationRepository.findBySlug(slug)
                .map(reservation -> reservation.isActiveAt(now) && reservation.getCreatorId().equals(creatorId))
                .orElse(false);
    }

    /** slug를 쓰기 시작할 때 남아 있는 예약(본인 예약이나 만료된 예약)을 지운다. */
    void clearReservation(String slug) {
        reservationRepository.findBySlug(slug).ifPresent(reservationRepository::delete);
    }

    /** 버린 slug를 예약한다. 같은 slug의 행이 남아 있으면(만료·본인) 주인과 기간을 새로 잡는다. */
    void reserveReleased(String releasedSlug, Long creatorId, LocalDateTime now) {
        reservationRepository.findBySlug(releasedSlug).ifPresentOrElse(
                reservation -> reservation.renew(creatorId, now),
                () -> reservationRepository.save(CreatorSpaceSlugReservation.reserve(releasedSlug, creatorId, now))
        );
    }
}
