package kr.co.cking.auth.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자 ID/PW 로그인에 필요한 요청 본문이다. */
public record AdminLoginRequest(
        @NotBlank(message = "loginId는 필수입니다.")
        @Size(max = 100, message = "loginId는 100자 이하여야 합니다.") String loginId,
        @NotBlank(message = "password는 필수입니다.") String password
) {
}
