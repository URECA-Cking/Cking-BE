package kr.co.cking.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** JWT subject를 Controller Member ID 인자로 전달하는 Resolver를 검증한다. */
class CurrentMemberIdArgumentResolverTest {

    private final CurrentMemberIdArgumentResolver resolver = new CurrentMemberIdArgumentResolver();

    /** @CurrentMemberId Long 파라미터만 Resolver 대상인지 검증한다. */
    @Test
    void CurrentMemberId_Long_파라미터만_처리한다() throws Exception {
        assertThat(resolver.supportsParameter(parameter("currentMemberId"))).isTrue();
        assertThat(resolver.supportsParameter(parameter("notCurrentMemberId"))).isFalse();
    }

    /** 검증된 JWT의 subject를 Long Member ID로 Controller에 전달하는지 검증한다. */
    @Test
    void JWT_subject를_현재_Member_ID로_전달한다() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("17"), List.of()));

        Object memberId = resolver.resolveArgument(parameter("currentMemberId"), null, null, null);

        assertThat(memberId).isEqualTo(17L);
    }

    /** JWT 인증 정보가 없거나 subject 형식이 잘못되면 Controller 호출을 막는지 검증한다. */
    @Test
    void 유효한_JWT_Member_ID가_없으면_거절한다() throws Exception {
        assertThatThrownBy(() -> resolver.resolveArgument(parameter("currentMemberId"), null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("not-a-number"), List.of()));
        assertThatThrownBy(() -> resolver.resolveArgument(parameter("currentMemberId"), null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    /** required=false면 공개 API에서 인증 정보가 없을 때 거절 대신 null을 전달하는지 검증한다. */
    @Test
    void required가_false면_인증_정보가_없을_때_null을_전달한다() throws Exception {
        Method method = SampleController.class.getDeclaredMethod("optionalMemberId", Long.class);

        assertThat(resolver.resolveArgument(new MethodParameter(method, 0), null, null, null)).isNull();
    }

    /** 테스트마다 공유 SecurityContext를 비운다. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 지정한 테스트 Controller 메서드의 첫 번째 인자 메타데이터를 만든다. */
    private MethodParameter parameter(String methodName) throws Exception {
        Method method = SampleController.class.getDeclaredMethod(methodName, Long.class);
        return new MethodParameter(method, 0);
    }

    /** subject만 다른 최소 JWT를 만든다. */
    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    /** Resolver의 지원 여부와 주입 결과를 확인하기 위한 가상 Controller다. */
    private static class SampleController {

        /** 현재 Member ID 주입 대상 메서드다. */
        void currentMemberId(@CurrentMemberId Long memberId) {
        }

        /** 인증이 선택인 공개 API용 메서드다. */
        void optionalMemberId(@CurrentMemberId(required = false) Long memberId) {
        }

        /** 일반 Long 파라미터의 비교 대상 메서드다. */
        void notCurrentMemberId(Long memberId) {
        }
    }
}
