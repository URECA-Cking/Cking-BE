package kr.co.cking.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 선택 입력인 HTTP/HTTPS 외부 링크를 검증한다. */
@Documented
@Constraint(validatedBy = HttpUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpUrl {

    String message() default "유효한 HTTP 또는 HTTPS URL이어야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
