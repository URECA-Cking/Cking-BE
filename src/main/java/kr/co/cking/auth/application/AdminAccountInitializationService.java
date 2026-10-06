package kr.co.cking.auth.application;

import kr.co.cking.auth.repository.AdminAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 개발·시연 환경의 관리자 계정과 연결 Member를 한 번만 준비한다. */
@Service
@RequiredArgsConstructor
public class AdminAccountInitializationService {

    private final AdminAccountRepository adminAccountRepository;
    private final AdminAccountCreationService adminAccountCreationService;

    /**
     * 로그인 ID가 없을 때만 ADMIN Member와 BCrypt 자격 증명 계정을 함께 생성한다.
     *
     * <p>이 메서드는 의도적으로 앰비언트 트랜잭션을 사용하지 않는다. 생성은
     * {@link AdminAccountCreationService}의 별도 {@code REQUIRES_NEW} 트랜잭션에서 수행하고,
     * UNIQUE 충돌 뒤 재조회는 실패한 생성 트랜잭션과 분리된 새 스냅샷에서 실행한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void initialize(String loginId, String rawPassword) {
        validateLoginId(loginId);
        if (adminAccountRepository.existsByLoginId(loginId)) {
            return;
        }

        validateRawPassword(rawPassword);

        try {
            adminAccountCreationService.create(loginId, rawPassword);
        } catch (DataIntegrityViolationException exception) {
            if (adminAccountRepository.existsByLoginId(loginId)) {
                return;
            }
            throw exception;
        }
    }

    /** 비어 있는 로그인 ID로 관리자 계정 존재 여부를 조회하거나 생성하지 않도록 막는다. */
    private void validateLoginId(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalStateException("관리자 초기 로그인 ID를 설정해야 합니다.");
        }
    }

    /** 새 관리자 계정을 만들 때 비밀번호 없는 자격 증명이 저장되지 않도록 막는다. */
    private void validateRawPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalStateException("관리자 초기 비밀번호를 환경변수로 설정해야 합니다.");
        }
    }
}
