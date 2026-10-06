package kr.co.cking.interest.application.dto;

import java.util.List;

/**
 * 회원의 관심 분야 선택이다. 선택이 있으면 그 분류체계 버전을, 없으면 지금 선택 가능한 활성 분류체계 버전을 담는다
 * (활성 분류체계도 없으면 null). 코드는 노출 순서대로다.
 */
public record MemberInterests(String taxonomyVersion, List<String> interestCodes) {
}
