package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceProfileImage;
import kr.co.cking.creator.domain.CreatorSpaceSlugRule;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 활성 템플릿을 복사해 Space를 만든다(승인 트랜잭션, 그리고 Space가 없는 Creator의 첫 수정). 템플릿이 없거나 slug 규칙이
 * 유효하지 않으면 예외를 던져 호출 전체를 롤백한다. 기존 템플릿 데이터도 생성 직전에 검증한다.
 * 회원이 프로필 사진을 올려 둔 상태였다면 그 파일을 Space용 파일로 복사해 프로필 사진으로 연결한다. 회원 사진 URL만 가리키면
 * 회원이 사진을 교체·삭제할 때 Space 사진이 깨지므로 Space가 자기 파일을 갖는다. 복사에 실패하면 승인·저장을 막지 않고
 * 템플릿 기본 이미지로 둔다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CreatorSpaceService {

    private static final int MAX_FALLBACK_SUFFIX = 100;
    /** {@link CreatorSpaceProfileImageUploadService}와 같은 접두사라 같은 정리 작업이 회수한다. */
    private static final String SPACE_IMAGE_KEY_PREFIX = "creator-space-profile-images/";

    private final CreatorSpaceRepository spaceRepository;
    private final CreatorSpaceTemplateService templateService;
    private final CreatorSpaceSlugRegistry slugRegistry;
    private final Clock clock;
    private final CreatorSpaceTemplateImagePinner imagePinner;
    private final CreatorContentOutbox contentOutbox;
    private final CreatorRepository creatorRepository;
    private final MemberRepository memberRepository;
    private final ObjectStorage objectStorage;
    private final CreatorSpaceProfileImageRepository imageRepository;
    private final CreatorSpaceProfileImageUrlResolver imageUrlResolver;

    /** 이미 Space가 있으면 그대로 반환한다. */
    public CreatorSpace createFromActiveTemplateIfAbsent(Long creatorId) {
        return spaceRepository.findByCreatorId(creatorId)
                .orElseGet(() -> createNew(creatorId));
    }

    private CreatorSpace createNew(Long creatorId) {
        CreatorSpaceTemplate template = templateService.findActive()
                .orElseThrow(() -> new BusinessException(CreatorErrorCode.NO_ACTIVE_SPACE_TEMPLATE));
        // 템플릿 URL을 Space로 복사하기 전에 같은 트랜잭션에서 업로드한 템플릿 이미지를 고정한다. 읽은 뒤 관리자가 템플릿을
        // 바꾸고 정리 작업이 그 이미지의 삭제를 확정했다면 여기서 거절되고, 아니면 정리 작업이 이 Space의 참조를 보게 된다.
        imagePinner.pin(template.getProfileImageUrl(), template.getBannerImageUrl());
        LocalDateTime now = LocalDateTime.now(clock);
        String slug = availableSlug(buildSlug(template.getSlugRule(), creatorId), creatorId, now);
        slugRegistry.clearReservation(slug);
        CreatorSpace space = spaceRepository.save(CreatorSpace.fromTemplate(creatorId, template, slug));
        carryOverMemberProfileImage(creatorId, space);
        contentOutbox.enqueue(space);
        return space;
    }

    /**
     * 회원이 올려 둔 프로필 사진이 있으면 Space 소유 파일로 복사해 프로필 사진으로 연결한다. 기본 이미지(복귀 대상)는 템플릿 이미지로 남는다.
     * 원본이 이미 사라졌거나 저장소 오류로 복사하지 못하면 기록만 남기고 템플릿 이미지를 그대로 쓴다.
     */
    private void carryOverMemberProfileImage(Long creatorId, CreatorSpace space) {
        String memberKey = creatorRepository.findById(creatorId)
                .map(Creator::getMemberId)
                .flatMap(memberRepository::findById)
                .map(member -> member.getProfileImageKey())
                .orElse(null);
        if (memberKey == null) {
            return;
        }
        try {
            byte[] bytes = objectStorage.get(memberKey);
            String key = "%s%d/%s.jpg".formatted(SPACE_IMAGE_KEY_PREFIX, creatorId, UUID.randomUUID());
            Instant uploadedAt = clock.instant();
            imageRepository.save(CreatorSpaceProfileImage.uploading(key, creatorId, uploadedAt));
            objectStorage.put(key, bytes, "image/jpeg");
            imageRepository.markUploaded(key, uploadedAt);
            if (imageRepository.link(key, creatorId, uploadedAt) != 1) {
                throw new IllegalStateException("복사한 프로필 사진을 연결하지 못했습니다. key=" + key);
            }
            space.updateProfileImage(key, imageUrlResolver.resolve(key));
        } catch (RuntimeException exception) {
            log.warn("회원 프로필 사진을 Space로 복사하지 못해 템플릿 기본 이미지를 씁니다. creatorId={}", creatorId, exception);
        }
    }

    /**
     * 다른 Creator가 커스텀 slug로 이 자동 slug를 먼저 가져갔거나, 버린 slug로 예약해 두었을 수 있다.
     * 그때는 `-2`, `-3`처럼 번호를 붙여 비어 있는 값을 써서 승인이 slug 충돌로 실패하지 않게 한다.
     */
    private String availableSlug(String base, Long creatorId, LocalDateTime now) {
        if (!slugRegistry.isTaken(base, creatorId, now)) {
            return base;
        }
        for (int suffix = 2; suffix <= MAX_FALLBACK_SUFFIX; suffix++) {
            String candidate = base + "-" + suffix;
            if (candidate.length() > CreatorSpaceSlugRule.MAX_SLUG_LENGTH) {
                break;
            }
            if (!slugRegistry.isTaken(candidate, creatorId, now)) {
                return candidate;
            }
        }
        throw new BusinessException(CreatorErrorCode.SLUG_ALREADY_TAKEN);
    }

    private String buildSlug(String slugRule, Long creatorId) {
        if (!CreatorSpaceSlugRule.isValid(slugRule)) {
            throw new BusinessException(CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE);
        }
        String slug = CreatorSpaceSlugRule.apply(slugRule, creatorId);
        if (slug.length() > CreatorSpaceSlugRule.MAX_SLUG_LENGTH) {
            throw new BusinessException(CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE);
        }
        return slug;
    }
}
