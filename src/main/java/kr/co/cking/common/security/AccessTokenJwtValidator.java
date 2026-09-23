package kr.co.cking.common.security;

import java.util.Set;

import kr.co.cking.member.domain.MemberRole;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Access JWT가 Cking의 Member subject와 role Claim 형식을 지키는지 검증한다. */
@Component
public class AccessTokenJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_ACCESS_TOKEN = new OAuth2Error("invalid_token");
    private static final Set<String> MEMBER_ROLES = Set.of(MemberRole.USER.name(), MemberRole.ADMIN.name());

    /** subject의 양수 Member ID와 USER·ADMIN role Claim을 모두 확인한다. */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!isPositiveMemberId(token.getSubject()) || !MEMBER_ROLES.contains(token.getClaimAsString("role"))) {
            return OAuth2TokenValidatorResult.failure(INVALID_ACCESS_TOKEN);
        }
        return OAuth2TokenValidatorResult.success();
    }

    /** subject가 양수 Long 범위의 Member ID인지 판별한다. */
    private boolean isPositiveMemberId(String subject) {
        try {
            return Long.parseLong(subject) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
