package kr.co.cking.common.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Controller가 SecurityContext를 직접 읽지 않고 현재 Member ID를 받도록 연결한다. */
@Component
public class CurrentMemberIdArgumentResolver implements HandlerMethodArgumentResolver {

    /** {@link CurrentMemberId}가 선언된 Long 파라미터만 이 Resolver가 처리한다. */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentMemberId.class) && parameter.getParameterType().equals(Long.class);
    }

    /** 검증을 통과한 JWT subject를 Long Member ID로 변환해 Controller에 전달한다. */
    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication) || !authentication.isAuthenticated()) {
            if (!parameter.getParameterAnnotation(CurrentMemberId.class).required()) {
                return null;
            }
            throw new AuthenticationCredentialsNotFoundException("검증된 JWT 인증 정보가 필요합니다.");
        }

        try {
            return Long.valueOf(jwtAuthentication.getToken().getSubject());
        } catch (NumberFormatException exception) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject가 Member ID 형식이 아닙니다.", exception);
        }
    }
}
