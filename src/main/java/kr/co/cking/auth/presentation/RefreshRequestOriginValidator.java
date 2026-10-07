package kr.co.cking.auth.presentation;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import kr.co.cking.auth.domain.RefreshSessionType;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Cookie 기반 인증 요청이 설정된 same-site origin에서 왔는지 검증한다. */
@Component
public class RefreshRequestOriginValidator {

    private final Set<String> userAllowedOrigins;
    private final Set<String> adminAllowedOrigins;

    /** 사용자·관리자 Web 세션별 허용 Origin을 Refresh·Logout CSRF 검증에 사용한다. */
    public RefreshRequestOriginValidator(
            @Value("${cking.auth.refresh.user-allowed-origins:}") String[] userAllowedOrigins,
            @Value("${cking.auth.refresh.admin-allowed-origins:}") String[] adminAllowedOrigins
    ) {
        this.userAllowedOrigins = allowedOriginSet(userAllowedOrigins);
        this.adminAllowedOrigins = allowedOriginSet(adminAllowedOrigins);
    }

    /** Origin 헤더가 요청 Web 세션 유형의 허용 Origin과 정확히 일치할 때만 통과시킨다. */
    public void validate(String origin, RefreshSessionType sessionType) {
        Objects.requireNonNull(sessionType, "sessionType은 필수입니다.");
        if (!StringUtils.hasText(origin) || !allowedOrigins(sessionType).contains(origin)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    /** 기존 사용자 Web 호출자의 호환을 위해 USER_WEB Origin 정책으로 검증한다. */
    @Deprecated(forRemoval = false)
    public void validate(String origin) {
        validate(origin, RefreshSessionType.USER_WEB);
    }

    /** 비어 있는 설정을 제거한 뒤 변경 불가능한 허용 Origin 집합으로 만든다. */
    private Set<String> allowedOriginSet(String[] origins) {
        return Set.copyOf(Arrays.stream(origins)
                .filter(StringUtils::hasText)
                .toList());
    }

    /** 요청 Web 세션 유형에 대응하는 허용 Origin 집합을 반환한다. */
    private Set<String> allowedOrigins(RefreshSessionType sessionType) {
        return sessionType == RefreshSessionType.ADMIN_WEB ? adminAllowedOrigins : userAllowedOrigins;
    }
}
