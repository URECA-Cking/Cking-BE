package kr.co.cking.common.config;

import java.util.List;

import kr.co.cking.common.security.CurrentMemberIdArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 인증 Principal을 Controller 인자로 전달할 MVC 확장을 등록한다. */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentMemberIdArgumentResolver currentMemberIdArgumentResolver;

    /** @CurrentMemberId 파라미터를 처리할 Resolver를 MVC 호출 흐름에 추가한다. */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentMemberIdArgumentResolver);
    }
}
