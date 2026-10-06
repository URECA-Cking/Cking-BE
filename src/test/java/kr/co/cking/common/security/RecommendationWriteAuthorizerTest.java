package kr.co.cking.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class RecommendationWriteAuthorizerTest {

    @Mock MemberRepository memberRepository;
    @InjectMocks RecommendationWriteAuthorizer authorizer;

    @Test
    void API_Key_주체는_DB를_조회하지_않고_통과한다() {
        assertThatCode(() -> authorizer.requireWriteAccess(new RecommendationApiKeyAuthentication()))
                .doesNotThrowAnyException();

        verifyNoInteractions(memberRepository);
    }

    @Test
    void JWT_회원이_DB에서도_ADMIN이면_통과한다() {
        given(memberRepository.findById(1L))
                .willReturn(Optional.of(new Member("admin", null, null, MemberRole.ADMIN)));

        assertThatCode(() -> authorizer.requireWriteAccess(jwt("1"))).doesNotThrowAnyException();
    }

    @Test
    void JWT_회원이_DB에서_ADMIN이_아니면_FORBIDDEN이다() {
        given(memberRepository.findById(2L))
                .willReturn(Optional.of(new Member("user", null, null, MemberRole.USER)));

        assertThatThrownBy(() -> authorizer.requireWriteAccess(jwt("2")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
    }

    @Test
    void 존재하지_않는_회원은_RESOURCE_NOT_FOUND다() {
        given(memberRepository.findById(3L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authorizer.requireWriteAccess(jwt("3")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void 인증_주체가_없거나_익명이거나_알_수_없는_타입이면_UNAUTHORIZED로_거부한다() {
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        Authentication unknown = new TestingAuthenticationToken("someone", "pw", "ROLE_ADMIN");

        for (Authentication authentication : new Authentication[] {null, anonymous, unknown}) {
            assertThatThrownBy(() -> authorizer.requireWriteAccess(authentication))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED));
        }
        verifyNoInteractions(memberRepository);
    }

    @Test
    void 회원_ID_형식이_아닌_JWT_subject는_UNAUTHORIZED다() {
        assertThatThrownBy(() -> authorizer.requireWriteAccess(jwt("not-a-number")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED));
    }

    private JwtAuthenticationToken jwt(String subject) {
        Jwt token = Jwt.withTokenValue("t").header("alg", "none").claim("sub", subject).build();
        return new JwtAuthenticationToken(token, List.of());
    }
}
