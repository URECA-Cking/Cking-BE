package kr.co.cking.creator.domain;

import java.time.Duration;
import kr.co.cking.member.domain.MemberNickname;

/** Creator 활동명은 Member 닉네임과 같은 형식·정규화·14일 정책을 공유하되 별도 namespace를 쓴다. */
public record CreatorActivityName(String value, String normalized) {

    public static final int MAX_LENGTH = MemberNickname.MAX_LENGTH;
    public static final Duration CHANGE_INTERVAL = MemberNickname.CHANGE_INTERVAL;
    public static final Duration RESERVATION_PERIOD = MemberNickname.RESERVATION_PERIOD;

    public CreatorActivityName {
        MemberNickname nickname = MemberNickname.of(value);
        if (!nickname.normalized().equals(normalized)) {
            throw new IllegalArgumentException("활동명 정규화 값이 올바르지 않습니다.");
        }
    }

    public static CreatorActivityName of(String value) {
        MemberNickname nickname = MemberNickname.of(value);
        return new CreatorActivityName(nickname.value(), nickname.normalized());
    }

    /** 기존 Creator·레거시 테스트에만 쓰는 공개 임시 활동명이다. */
    public static CreatorActivityName temporaryFor(Long identity) {
        return of("creator-" + identity);
    }
}
