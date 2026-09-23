package kr.co.cking.auth.presentation;

import java.util.Arrays;
import java.util.Set;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Cookie 기반 인증 요청이 설정된 same-site origin에서 왔는지 검증한다. */
@Component
public class RefreshRequestOriginValidator {

    private final Set<String> allowedOrigins;

    /** CORS와 같은 허용 origin 목록을 Refresh·Logout CSRF 검증에 사용한다. */
    public RefreshRequestOriginValidator(@Value("${cking.cors.allowed-origins:}") String[] allowedOrigins) {
        this.allowedOrigins = Set.copyOf(Arrays.stream(allowedOrigins)
                .filter(StringUtils::hasText)
                .toList());
    }

    /** Origin 헤더가 설정된 허용 origin과 정확히 일치할 때만 통과시킨다. */
    public void validate(String origin) {
        if (!StringUtils.hasText(origin) || !allowedOrigins.contains(origin)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
