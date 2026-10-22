package kr.co.cking.recommendation.application;

import java.util.Collection;
import java.util.Map;

/** Creator의 현재 소개 원문을 읽는 포트다. creator 모듈이 구현해 recommendation이 creator_space를 직접 읽지 않게 한다. */
public interface CreatorIntroductions {

    /** Space가 없는 Creator는 결과에서 빠진다. 소개가 비어 있으면 null 대신 빈 문자열이다. */
    Map<Long, String> findIntroTexts(Collection<Long> creatorIds);
}
