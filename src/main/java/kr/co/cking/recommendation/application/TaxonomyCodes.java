package kr.co.cking.recommendation.application;

import java.util.Set;

/** 추천 근거가 참조하는 분류체계의 분야 코드를 읽는 포트다. interest 모듈이 구현해 recommendation이 interest를 알지 않게 한다. */
public interface TaxonomyCodes {

    /** 적재용이다. 현재 활성인 버전·해시일 때만 그 버전의 활성 분야 코드를 돌려주고, 아니면 빈 집합이다. */
    Set<String> activeCodes(String version, String hash);

    /** 조회용이다. 활성 여부와 무관하게 등록된 버전·해시의 모든 분야 코드를 돌려주고, 등록되지 않았으면 빈 집합이다. */
    Set<String> registeredCodes(String version, String hash);
}
