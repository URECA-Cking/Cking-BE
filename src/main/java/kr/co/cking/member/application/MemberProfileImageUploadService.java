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
        requireProfileOwner(memberId);
        NormalizedImage image = imageProcessor.process(imageBytes);
        String objectKey = objectKey(memberId);
        profileImageRepository.save(MemberProfileImage.uploading(objectKey, memberId, clock.instant()));
        objectStorage.put(objectKey, image.normalizedImageBytes(), CONTENT_TYPE);
        profileImageRepository.markUploaded(objectKey, clock.instant());
        return objectKey;
    }

    /** 정규화를 앱 밖 파이프라인에 맡길 때 권한 확인과 업로드 기록만 먼저 만든다(#780). */
    public String reserve(Long memberId) {
        requireProfileOwner(memberId);
        String objectKey = objectKey(memberId);
        profileImageRepository.save(MemberProfileImage.uploading(objectKey, memberId, clock.instant()));
        return objectKey;
    }

    public boolean markUploaded(String objectKey) {
        return profileImageRepository.markUploaded(objectKey, clock.instant()) == 1;
    }

    private void requireProfileOwner(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        if (creatorQueryService.isCreatorMember(memberId)) {
            throw new BusinessException(MemberErrorCode.CREATOR_PROFILE_MANAGED_IN_SPACE);
        }
    }

    static String objectKey(Long memberId) {
        return "member-profile-images/%d/%s.jpg".formatted(memberId, UUID.randomUUID());
    }
}
