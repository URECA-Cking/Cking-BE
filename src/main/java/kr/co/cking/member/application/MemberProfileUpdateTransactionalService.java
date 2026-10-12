package kr.co.cking.member.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberErrorCode;
import kr.co.cking.member.repository.MemberProfileImageRepository;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로필의 닉네임과 사진 메타데이터를 함께 저장한다. */
@Service
@RequiredArgsConstructor
class MemberProfileUpdateTransactionalService {

    private final MemberRepository memberRepository;
    private final MemberProfileImageRepository profileImageRepository;
    private final MemberNicknameTransactionalService nicknameService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public void update(Long memberId, MemberProfileUpdateService.UpdateFields fields) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        nicknameService.change(member, fields.nickname());
        if (fields.profileImageKeyProvided()) {
            updateProfileImage(member, fields.profileImageKey());
        }
        memberRepository.saveAndFlush(member);
    }

    private void updateProfileImage(Member member, String requestedKey) {
        if (requestedKey != null && requestedKey.isBlank()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        String currentKey = member.getProfileImageKey();
        if (Objects.equals(currentKey, requestedKey)) {
            return;
        }

        Instant now = clock.instant();
        if (requestedKey != null && profileImageRepository.linkToProfile(requestedKey, member.getMemberId(), now) != 1) {
            throw new BusinessException(MemberErrorCode.PROFILE_IMAGE_UNAVAILABLE);
        }
        member.updateProfileImageKey(requestedKey);
        if (currentKey != null && profileImageRepository.markDeletePending(currentKey, member.getMemberId(), now) == 1) {
            eventPublisher.publishEvent(new MemberProfileImageDeletionRequestedEvent(currentKey));
        }
    }
}
