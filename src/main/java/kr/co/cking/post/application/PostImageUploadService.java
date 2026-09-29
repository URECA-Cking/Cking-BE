package kr.co.cking.post.application;

import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.post.application.image.PostImageProcessor;
import kr.co.cking.post.domain.CreatorPostImage;
import kr.co.cking.post.repository.CreatorPostImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * 게시글 이미지 한 장을 업로드한다(이슈 #318).
 *
 * <p>기록을 UPLOADING으로 먼저 남긴 뒤 저장소에 put하고 UPLOADED로 바꾼다. put이나 상태 변경이 중간에 실패해도
 * 기록이 남아 있으므로 정리 스케줄러가 저장소 객체까지 지운다. 저장소 호출이 DB Transaction을 붙잡지 않도록
 * 이 흐름 전체를 하나의 Transaction으로 묶지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PostImageUploadService {

    static final String CONTENT_TYPE = "image/jpeg";

    private final PostAuthorLookup authorLookup;
    private final PostImageProcessor imageProcessor;
    private final CreatorPostImageRepository imageRepository;
    private final ObjectStorage objectStorage;
    private final Clock clock;

    /** @return 게시글 작성·수정 때 전달할 이미지 key */
    public String upload(Long memberId, byte[] imageBytes) {
        Creator creator = authorLookup.requireCreator(memberId);
        NormalizedImage image = imageProcessor.process(imageBytes);
        String objectKey = objectKey(creator.getCreatorId());

        imageRepository.save(CreatorPostImage.uploading(objectKey, creator.getCreatorId(), clock.instant()));
        objectStorage.put(objectKey, image.normalizedImageBytes(), CONTENT_TYPE);
        imageRepository.markUploaded(objectKey, clock.instant());
        return objectKey;
    }

    static String objectKey(Long creatorId) {
        return "post-images/%d/%s.jpg".formatted(creatorId, UUID.randomUUID());
    }
}
