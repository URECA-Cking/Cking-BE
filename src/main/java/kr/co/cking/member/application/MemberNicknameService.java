package kr.co.cking.member.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.domain.MemberErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** DB 고유성 경합을 업무 오류로 변환하는 공개 닉네임 Command 진입점이다. */
@Service
@RequiredArgsConstructor
public class MemberNicknameService {

    private final MemberNicknameTransactionalService transactionalService;

    public MemberNicknameResult change(Long memberId, String requestedNickname) {
        try {
            return transactionalService.change(memberId, requestedNickname);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_TAKEN);
        }
    }

    public record MemberNicknameResult(
            String nickname,
            String previousNickname,
            java.time.LocalDateTime nicknameChangeableAt
    ) {
        static MemberNicknameResult from(kr.co.cking.member.domain.Member member) {
            return new MemberNicknameResult(
                    member.getNickname(), member.getPreviousNickname(), member.nicknameChangeableAt());
        }
    }
}
