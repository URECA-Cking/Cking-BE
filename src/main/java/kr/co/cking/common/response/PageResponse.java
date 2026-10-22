package kr.co.cking.common.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Page 방식 목록 API 공통 응답(API 명세 §1.7). Spring {@link Page}를 그대로 노출하면
 * pageable/sort 같은 내부 구현 필드까지 새 나가므로 이 형태로 변환해서 내려준다.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }
}
