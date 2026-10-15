package kr.co.cking.member.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberErrorCode;
import kr.co.cking.member.domain.MemberNickname;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/** 닉네임 변경·예약 갱신을 호출자 트랜잭션에 합쳐 처리한다. */
@Service
@RequiredArgsConstructor
class MemberNicknameTransactionalService {

    private final MemberRepository memberRepository;
    private final MemberNicknameRegistry nicknameRegistry;
    private final MemberNicknameContentPolicy contentPolicy;
    private final Clock clock;

    @Transactional
    public MemberNicknameService.MemberNicknameResult change(Long memberId, String requestedNickname) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        MemberNickname requested = parseAndValidate(requestedNickname);
        if (member.hasCurrentNickname(requested)) {
            return MemberNicknameService.MemberNicknameResult.from(member);
        }
        MemberNicknameService.MemberNicknameResult result = change(member, requested);
        memberRepository.saveAndFlush(member);
        return result;
    }

    /** 이미 호출자가 Member 행 잠금을 보유한 통합 프로필 수정 transaction에서 재사용한다. */
    public MemberNicknameService.MemberNicknameResult change(Member member, String requestedNickname) {
        return change(member, parseAndValidate(requestedNickname));
    }

    private MemberNicknameService.MemberNicknameResult change(Member member, MemberNickname requested) {
        if (member.hasCurrentNickname(requested)) {
            return MemberNicknameService.MemberNicknameResult.from(member);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (!member.hasNickname()) {
            setInitial(member, requested, now);
        } else {
            changeOrRevert(member, requested, now);
        }
        return MemberNicknameService.MemberNicknameResult.from(member);
    }

    private MemberNickname parseAndValidate(String requestedNickname) {
        try {
            MemberNickname nickname = MemberNickname.of(requestedNickname);
            contentPolicy.validate(nickname);
            return nickname;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private void setInitial(Member member, MemberNickname nickname, LocalDateTime now) {
        if (nicknameRegistry.isTaken(nickname, member.getMemberId(), now)) {
            throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_TAKEN);
        }
        member.setInitialNickname(nickname);
    }

    private void changeOrRevert(Member member, MemberNickname nickname, LocalDateTime now) {
        boolean revert = member.getPreviousNicknameNormalized() != null
                && member.getPreviousNicknameNormalized().equals(nickname.normalized())
                && nicknameRegistry.findOwnedActive(nickname, member.getMemberId(), now).isPresent();
        if (revert) {
            revert(member, nickname, now);
            return;
        }
        if (!member.canChangeNicknameAt(now)) {
            throw new BusinessException(MemberErrorCode.NICKNAME_CHANGE_TOO_SOON);
        }
        if (nicknameRegistry.isTaken(nickname, member.getMemberId(), now)) {
            throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_TAKEN);
        }
        MemberNickname released = new MemberNickname(member.getNickname(), member.getNicknameNormalized());
        member.changeNickname(nickname, now);
        nicknameRegistry.clear(nickname);
        nicknameRegistry.reserveReleased(released, member.getMemberId(), now);
    }

    private void revert(Member member, MemberNickname nickname, LocalDateTime now) {
        MemberNickname released = new MemberNickname(member.getNickname(), member.getNicknameNormalized());
        member.revertNickname();
        nicknameRegistry.clear(nickname);
        nicknameRegistry.reserveReleased(released, member.getMemberId(), now);
    }
}
