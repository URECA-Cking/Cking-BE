package kr.co.cking.member.application;

import java.time.Clock;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.member.domain.MemberErrorCode;
import kr.co.cking.member.application.image.MemberProfileImageProcessor;
import kr.co.cking.member.domain.MemberProfileImage;
import kr.co.cking.member.repository.MemberProfileImageRepository;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 개인 프로필 사진을 소유자 기록과 함께 저장한다. 저장소 호출은 DB transaction 밖에서 실행한다. */
@Service
@RequiredArgsConstructor
public class MemberProfileImageUploadService {

    static final String CONTENT_TYPE = "image/jpeg";

    private final MemberRepository memberRepository;
    private final CreatorQueryService creatorQueryService;
    private final MemberProfileImageProcessor imageProcessor;
    private final MemberProfileImageRepository profileImageRepository;
    private final ObjectStorage objectStorage;
    private final Clock clock;

    public String upload(Long memberId, byte[] imageBytes) {
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        if (creatorQueryService.isCreatorMember(memberId)) {
            throw new BusinessException(MemberErrorCode.CREATOR_PROFILE_MANAGED_IN_SPACE);
        }
        NormalizedImage image = imageProcessor.process(imageBytes);
        String objectKey = objectKey(memberId);
        profileImageRepository.save(MemberProfileImage.uploading(objectKey, memberId, clock.instant()));
        objectStorage.put(objectKey, image.normalizedImageBytes(), CONTENT_TYPE);
        profileImageRepository.markUploaded(objectKey, clock.instant());
        return objectKey;
    }

    static String objectKey(Long memberId) {
        return "member-profile-images/%d/%s.jpg".formatted(memberId, UUID.randomUUID());
    }
}
