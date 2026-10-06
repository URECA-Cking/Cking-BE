package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import kr.co.cking.auth.repository.AdminAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class AdminAccountInitializationServiceTest {

    private static final String LOGIN_ID = "admin";

    @Mock private AdminAccountRepository adminAccountRepository;
    @Mock private AdminAccountCreationService adminAccountCreationService;

    private AdminAccountInitializationService initializationService;
    private String rawPassword;

    /** 충돌 처리 분기를 단위 테스트할 초기화 서비스를 준비한다. */
    @BeforeEach
    void setUp() {
        rawPassword = UUID.randomUUID().toString();
        initializationService = new AdminAccountInitializationService(
                adminAccountRepository,
                adminAccountCreationService
        );
    }

    /** 이미 생성된 계정은 비밀번호 없이도 생성 서비스를 호출하지 않는지 검증한다. */
    @Test
    void skipsCreationWhenAccountAlreadyExists() {
        when(adminAccountRepository.existsByLoginId(LOGIN_ID)).thenReturn(true);

        initializationService.initialize(LOGIN_ID, " ");

        verifyNoInteractions(adminAccountCreationService);
    }

    /** 경쟁에서 진 생성이 UNIQUE 충돌 후 다른 인스턴스의 계정을 발견하면 정상 종료하는지 검증한다. */
    @Test
    void treatsExistingAccountAfterConstraintViolationAsConcurrentSuccess() {
        when(adminAccountRepository.existsByLoginId(LOGIN_ID)).thenReturn(false, true);
        doThrow(new DataIntegrityViolationException("uk_admin_account_login_id"))
                .when(adminAccountCreationService).create(LOGIN_ID, rawPassword);

        initializationService.initialize(LOGIN_ID, rawPassword);

        verify(adminAccountCreationService).create(LOGIN_ID, rawPassword);
    }

    /** 충돌 후에도 계정이 없으면 예상하지 못한 DB 오류를 그대로 전파하는지 검증한다. */
    @Test
    void rethrowsConstraintViolationWhenAccountIsStillAbsent() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException("unexpected");
        when(adminAccountRepository.existsByLoginId(LOGIN_ID)).thenReturn(false, false);
        doThrow(exception).when(adminAccountCreationService).create(LOGIN_ID, rawPassword);

        assertThatThrownBy(() -> initializationService.initialize(LOGIN_ID, rawPassword))
                .isSameAs(exception);
    }
}
