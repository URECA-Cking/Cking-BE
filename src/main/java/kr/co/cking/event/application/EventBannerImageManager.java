package kr.co.cking.event.application;

import java.time.Clock;
import java.util.Objects;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.repository.EventBannerImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Event 잠금(또는 생성 트랜잭션)을 보유한 호출자가 배너 연결·교체·제거를 Event 변경과 원자적으로 적용한다(이슈 #748).
 * 연결은 본인이 올린 업로드 완료 키만 받고, 교체·제거로 연결이 풀린 이전 배너는 삭제 대기로 돌려 정리 작업이 회수한다.
 */
@Component
@RequiredArgsConstructor
class EventBannerImageManager {

    private final EventBannerImageRepository imageRepository;
    private final EventBannerImageUrlResolver urlResolver;
    private final Clock clock;

    /** 키가 현재 배너와 같으면 아무것도 하지 않는다. null이면 배너를 떼고, 그 밖의 키는 연결하거나 {@code INVALID_EVENT_BANNER_IMAGE}다. */
    void apply(Event event, String imageKey) {
        if (Objects.equals(imageKey, event.getBannerImageKey())) {
            return;
        }
        String oldKey = event.getBannerImageKey();
        var now = clock.instant();
        if (imageKey == null) {
            event.changeBannerImage(null, null);
        } else {
            if (imageKey.isBlank() || imageRepository.link(imageKey, event.getCreatorId(), now) != 1) {
                throw new BusinessException(EventErrorCode.INVALID_EVENT_BANNER_IMAGE);
            }
            event.changeBannerImage(imageKey, urlResolver.resolve(imageKey));
        }
        if (oldKey != null) {
            imageRepository.markDeletePending(oldKey, event.getCreatorId(), now);
        }
    }

    /** 논리 삭제된 Event의 배너를 삭제 대기로 돌린다. Event 행의 키는 기록으로 남기며, 삭제된 Event는 조회되지 않는다. */
    void release(Long creatorId, String imageKey) {
        if (imageKey != null) {
            imageRepository.markDeletePending(imageKey, creatorId, clock.instant());
        }
    }
}
