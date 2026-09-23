package kr.co.cking.auth.presentation;

import jakarta.validation.constraints.NotBlank;

/** 1회용 Login Code를 Access Token으로 교환하기 위한 요청 본문이다. */
public record TokenExchangeRequest(
        @NotBlank(message = "code는 필수입니다.") String code
) {
}
