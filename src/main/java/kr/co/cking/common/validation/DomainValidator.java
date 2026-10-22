package kr.co.cking.common.validation;

/** 여러 도메인에서 공통으로 사용하는 단순 불변조건 검증을 제공한다. */
public final class DomainValidator {

    private DomainValidator() {
    }

    /** 필수 식별자가 양수인지 검증한다. */
    public static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + "는 양수여야 합니다.");
        }
    }
}
