package kr.co.cking.common.security;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 추천 결과 적재의 Application 계층 권한 확인이다. 적재 서비스는 인증 방식을 모르고, 여기서 인증 주체를 구분한다.
 * 배치 API Key 주체는 Security가 키를 이미 확인했으므로 통과하고, JWT 회원은 Security 1차 인가 뒤에도 DB의 ADMIN 역할을
 * 다시 확인한다(팀 인증 규칙). 그 밖의 주체(익명·알 수 없는 타입)는 모두 거부하므로 Security 설정이 잘못돼도 열리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RecommendationWriteAuthorizer {

    private final MemberRepository memberRepository;

    public void requireWriteAccess(Authentication authentication) {
        if (authentication instanceof RecommendationApiKeyAuthentication && authentication.isAuthenticated()) {
            return;
        }
        if (authentication instanceof JwtAuthenticationToken jwt && authentication.isAuthenticated()) {
            requireAdmin(memberId(jwt));
            return;
        }
        throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
    }

    private Long memberId(JwtAuthenticationToken jwt) {
        try {
            return Long.valueOf(jwt.getToken().getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    private void requireAdmin(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (member.getRole() != MemberRole.ADMIN) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
