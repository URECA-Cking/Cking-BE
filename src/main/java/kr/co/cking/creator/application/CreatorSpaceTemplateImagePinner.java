package kr.co.cking.creator.application;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.CreatorSpaceTemplateImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 템플릿에 저장하려는 URL에 관리자가 올린 템플릿 이미지가 있으면 그 이미지 기록을 "쓰는 중"으로 고정한다(이슈 #598).
 * 템플릿 저장과 같은 트랜잭션에서 호출해야 한다. 고정이 잡은 행 잠금이 저장 트랜잭션이 끝날 때까지 유지되어, 정리 작업이
 * 같은 이미지를 삭제 확정하려면 기다렸다가 실패한다. 정리 작업이 이미 삭제를 확정한 이미지는 객체가 지워졌을 수 있으므로
 * 저장을 거절한다. 저장소 삭제는 DB 트랜잭션에 묶을 수 없어, 이 고정이 "삭제 확정"과 "쓰기 시작"을 직렬화하는 유일한 지점이다.
 *
 * <p>업로드 기록이 없는 URL(외부 URL, 정리 작업 도입 이전에 올린 이미지)은 추적 대상이 아니므로 그대로 허용한다.
 */
@Component @RequiredArgsConstructor
public class CreatorSpaceTemplateImagePinner {
    /** 업로드 서비스가 만드는 키. URL 어디에 들어 있든 찾도록 전체 일치가 아니라 포함 검색을 쓴다(정리 작업의 참조 대조와 같은 기준). */
    private static final Pattern TEMPLATE_IMAGE_KEY =
            Pattern.compile("creator-space-profile-images/template/(?:profile|banner)/[0-9a-f-]{36}\\.jpg");

    private final CreatorSpaceTemplateImageRepository imageRepository;
    private final Clock clock;

    /** URL에 들어 있는 템플릿 이미지를 모두 고정한다. 정리되었거나 정리 중인 이미지가 있으면 {@code INVALID_SPACE_TEMPLATE_IMAGE}다. */
    public void pin(String... urls) {
        Set<String> keys = new LinkedHashSet<>();
        for (String url : urls) {
            if (url == null) continue;
            Matcher matcher = TEMPLATE_IMAGE_KEY.matcher(url);
            while (matcher.find()) keys.add(matcher.group());
        }
        for (String key : keys) {
            if (imageRepository.pin(key, clock.instant()) == 0 && imageRepository.existsByObjectKey(key)) {
                throw new BusinessException(CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE);
            }
        }
    }
}
