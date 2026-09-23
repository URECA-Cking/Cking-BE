# AUTH-06 Access JWT + 인증 Principal 설계

## 범위

Login Code를 Access JWT로 교환하고, 검증된 `memberId`를 Controller에 전달하는 인증 경계를 구현한다.
Refresh Token 발급·회전·Cookie, 기존 업무 API의 호출자 `userId` 제거는 후속 작업 범위다.

## 흐름

```text
POST /api/auth/token { code }
  → LoginCodeService.consume(code)
  → Member 조회(memberId, role)
  → AccessTokenService.issue(memberId, role)
  → ApiResponse<TokenResponse>

Authorization: Bearer <JWT>
  → NimbusJwtDecoder(HS256 서명, exp, iss 검증)
  → JwtAuthenticationConverter(role → ROLE_USER | ROLE_ADMIN)
  → SecurityContext
  → @CurrentMemberId Long
```

## JWT 정책

- 알고리즘은 HS256이며, `cking.auth.jwt.secret`에는 Base64로 인코딩한 256비트 이상 비밀값을 둔다.
- Issuer는 `cking.auth.jwt.issuer`(기본값 `cking`)다.
- TTL은 `cking.auth.jwt.access-token-ttl`(기본값 `PT30M`)이다.
- Claim은 `iss`, `sub`(Long `memberId`의 문자열), `role`(`USER` 또는 `ADMIN`), `iat`, `exp`다.
- Resource Server Decoder는 HS256 서명, 표준 시간 Claim, Issuer를 모두 검증한다. 검증 실패는 공통
  `UNAUTHORIZED`로 응답한다.

## 계층과 책임

| 구성요소 | 책임 |
| --- | --- |
| `AccessTokenService` | 존재하는 Member의 역할을 조회하고 JWT를 발급한다. |
| `AuthController` | Login Code 요청을 검증하고 Token 교환 응답을 만든다. |
| `JwtAuthenticationConverter` | `role` Claim을 Spring Security 권한으로 변환한다. |
| `CurrentMemberIdArgumentResolver` | 검증된 JWT의 `sub`를 Controller의 `Long` 인자로 전달한다. |
| `SecurityConfig` | Resource Server를 연결하고 Token 교환·기존 API의 현재 공개 규칙을 유지한다. |

Application/Domain은 `Jwt`, `Authentication`, `SecurityContext`를 알지 않는다. Controller는 JWT를 직접
파싱하지 않고 `@CurrentMemberId`만 사용한다.

## API·전환 규칙

- `POST /api/auth/token`은 공개 API이며 Login Code를 한 번 소비한 후 Access Token만 반환한다.
- Login Code가 유효하지 않으면 `INVALID_LOGIN_CODE`(401), 형식이 잘못되면 `VALIDATION_FAILED`(400)를
  반환한다.
- JWT가 필요한 신규 API부터 `@CurrentMemberId`를 사용한다. 기존 API의 Request `userId` 계약과
  `/api/**` 공개 규칙은 이 작업에서 변경하지 않는다. 이후 각 API의 호출자 식별을 전환할 때 해당
  경로를 인증 필수로 바꾼다.
- `role`은 `ROLE_USER` 또는 `ROLE_ADMIN`으로 변환한다. Creator는 JWT 권한이나 Claim으로 표현하지 않는다.

## 검증

- Token 교환은 Claim, TTL, 응답 형식을 검증한다.
- Decoder는 정상 JWT와 서명·Issuer·만료 오류를 검증한다.
- Resolver는 SecurityContext의 JWT subject만 전달하고, 비정상 subject는 인증 실패로 처리한다.
