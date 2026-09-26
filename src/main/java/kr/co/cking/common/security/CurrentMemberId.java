package kr.co.cking.common.security;

import io.swagger.v3.oas.annotations.Parameter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 검증된 JWT의 subject에 들어 있는 현재 Member ID를 Controller 인자로 주입한다. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Parameter(hidden = true)
public @interface CurrentMemberId {

    /** false면 공개 API에서 토큰이 없을 때 예외 대신 null을 주입한다. */
    boolean required() default true;
}
