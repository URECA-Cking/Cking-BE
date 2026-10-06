package kr.co.cking.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** 배포 후 검증이 ALB를 거쳐 실제로 응답하는 버전을 확인할 수 있도록 응답 헤더에 이미지 태그를 싣는다. */
@Component
public class ImageTagHeaderFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Image-Tag";

    private final String imageTag;

    public ImageTagHeaderFilter(@Value("${info.image.tag:}") String imageTag) {
        this.imageTag = imageTag;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (StringUtils.hasText(imageTag)) {
            response.setHeader(HEADER, imageTag);
        }
        filterChain.doFilter(request, response);
    }
}
