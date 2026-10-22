package kr.co.cking.auth.application.dto;

/** Frontend callback에 담을 Login Code와, 그 Code를 교환할 수 있는 브라우저에만 쿠키로 줄 값을 전달한다. */
public record IssuedLoginCode(
        String code,
        String browserBinding
) {
}
