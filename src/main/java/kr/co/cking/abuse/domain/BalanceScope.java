package kr.co.cking.abuse.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import java.util.Objects;

/** 공용 또는 Creator 전용 응모권 잔액 풀을 식별한다. */
public record BalanceScope(Type type, Long creatorId) {

    public enum Type {
        COMMON,
        CREATOR
    }

    public BalanceScope {
        Objects.requireNonNull(type, "type은 필수입니다.");
        if (type == Type.COMMON && creatorId != null) {
            throw new IllegalArgumentException("COMMON 잔액 범위에는 creatorId를 지정할 수 없습니다.");
        }
        if (type == Type.CREATOR) {
            requirePositive(creatorId, "creatorId");
        }
    }

    public static BalanceScope common() {
        return new BalanceScope(Type.COMMON, null);
    }

    public static BalanceScope creator(Long creatorId) {
        return new BalanceScope(Type.CREATOR, creatorId);
    }

    /** Redis key와 Evidence에서 사용하는 안정적인 범위 표현이다. */
    public String canonicalValue() {
        return type == Type.COMMON ? "COMMON" : "CREATOR:" + creatorId;
    }
}
