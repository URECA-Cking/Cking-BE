package kr.co.cking.member.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.domain.MemberErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** 닉네임과 프로필 사진을 하나의 DB transaction으로 교체하는 공개 Command 진입점이다. */
@Service
@RequiredArgsConstructor
public class MemberProfileUpdateService {

    private final MemberProfileUpdateTransactionalService transactionalService;

    public void update(Long memberId, UpdateFields fields) {
        try {
            transactionalService.update(memberId, fields);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_TAKEN);
        }
    }

    public record UpdateFields(String nickname, String profileImageKey, boolean profileImageKeyProvided) {
    }
}
