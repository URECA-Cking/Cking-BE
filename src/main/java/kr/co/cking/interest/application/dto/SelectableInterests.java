package kr.co.cking.interest.application.dto;

import java.util.List;

/** 회원이 고를 수 있는 관심 분야 목록이다. 활성 분류체계가 없으면 버전은 null이고 목록은 비어 있다. */
public record SelectableInterests(String taxonomyVersion, int maxSelection, List<Item> items) {

    public record Item(String interestCode, String name, int displayOrder) {
    }
}
