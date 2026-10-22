package kr.co.cking.event.application;

import java.time.Clock;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.ImagePolicy;
import kr.co.cking.common.image.InvalidImageException;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.domain.EventBannerImage;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.repository.EventBannerImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Event 배너 이미지를 올리고 생성·수정 요청에 쓸 imageKey를 돌려준다(이슈 #748).
 *
 * <p>키는 CDN 공개·서버 쓰기 권한이 이미 열린 Creator Space 이미지 접두사 아래 {@code event-banner/}에 만든다.
 * 새 접두사는 인프라(버킷 정책·서버 권한) 변경이 필요해서다. 업로드 기록과 정리는 Space 이미지와 별도 테이블로 나눈다.
 */
@Service
@RequiredArgsConstructor
public class EventBannerImageUploadService {
    /** Creator Space 배너와 같은 기준이다. 가로세로 비율은 강제하지 않고 화면이 잘라 보여 준다. */
    static final ImagePolicy BANNER_POLICY = new ImagePolicy(600, 200, 2048);
    private static final String KEY_FORMAT = "creator-space-profile-images/%d/event-banner/%s.jpg";
    private final CreatorRepository creatorRepository;
    private final EventBannerImageRepository imageRepository;
    private final ImageNormalizer imageNormalizer;
    private final ObjectStorage objectStorage;
    private final Clock clock;

    public String upload(Long memberId, byte[] bytes) {
        Creator creator = creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        NormalizedImage image;
        try {
            image = imageNormalizer.normalize(bytes, BANNER_POLICY);
        } catch (InvalidImageException exception) {
            throw new BusinessException(EventErrorCode.INVALID_EVENT_BANNER_IMAGE);
        }
        String key = KEY_FORMAT.formatted(creator.getCreatorId(), UUID.randomUUID());
        imageRepository.save(EventBannerImage.uploading(key, creator.getCreatorId(), clock.instant()));
        // 업로드 기록을 먼저 남겨, 저장소에 올린 뒤 실패해도 정리 작업이 회수한다.
        objectStorage.put(key, image.normalizedImageBytes(), "image/jpeg");
        imageRepository.markUploaded(key, clock.instant());
        return key;
    }
}
