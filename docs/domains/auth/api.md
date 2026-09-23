# Auth API

이 문서는 구현된 Auth 외부 API 계약이다. OAuth2 로그인 완료 후 Login Code를 Access JWT로 교환·갱신하고
Logout할 수 있으며,
기존 업무 API의 `userId` 계약과 `/api/**` 공개 규칙은 [공통 API 규약](../../common/api.md)을 그대로 따른다.

모든 구현 완료 후 응답은 공통 `ApiResponse` 봉투를 사용한다. 아래 JSON 예시는 `data` 값이다.

## OAuth 로그인 시작

### `GET /oauth2/authorization/google`

### `GET /oauth2/authorization/kakao`

- 권한: `PUBLIC`
- Spring Security OAuth2 Client의 기본 authorization endpoint를 사용해 각 Provider 인증 화면으로 redirect한다.
- Provider callback은 Spring Security OAuth2 Client가 처리한다. 별도 Controller API로 만들지 않는다.

성공하면 서버는 Frontend callback으로 Login Code만 전달한다.

```text
/oauth/callback?code=...
```

실패 또는 사용자의 로그인 취소도 같은 callback으로 redirect한다.

```text
/oauth/callback?error=<oauth-error>
```

| `error` 값 | 상황 | Frontend 처리 |
| --- | --- | --- |
| `access_denied` | 사용자가 Provider 로그인 또는 동의를 취소·거부함 | 취소 안내 후 로그인 화면으로 이동 |
| `provider_error` | Provider가 인증에 실패했거나 사용자 정보를 정상적으로 반환하지 않음 | 재시도 안내 |
| `login_processing_failed` | Cking이 OAuth 사용자 정규화·Member 연결을 완료하지 못함 | 재시도 안내; 반복되면 고객 지원 경로 안내 |

Redirect URL에는 성공 시 `code` 또는 실패 시 `error` 중 하나만 넣는다. Access JWT, Provider 원문 오류와
내부 예외 상세는 redirect URL에 포함하지 않는다.

## Token 교환

### `POST /api/auth/token`

- 권한: `PUBLIC`
- Request Body의 `code`는 Login Code 원문이다.

```json
{ "code": "..." }
```

Login Code를 검증하고 원자적으로 한 번 소비한 뒤 `memberId`로 30분 유효한 Access JWT를 발급한다. 응답과
함께 14일 유효한 `refresh_token` Cookie를 발급한다.
JWT는 `iss=cking`, `sub=memberId`, `role=USER|ADMIN`, `iat`, `exp` Claim을 포함한다.

```json
{
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresIn": 1800
}
```

## Access Token 갱신

### `POST /api/auth/refresh`

- 권한: `PUBLIC`
- Request Body: 없음
- Request Cookie: `refresh_token`
- `Authorization: Bearer` 헤더는 읽거나 검증하지 않는다. 만료된 Access JWT가 함께 전송되어도
  Refresh Cookie 흐름을 수행한다.

Cookie의 opaque Refresh Token을 Redis Lua로 원자적으로 한 번 소비하고, 같은 Member의 새 Access JWT와
새 Refresh Cookie를 발급한다. 기존 Refresh Token은 즉시 폐기되어 재사용할 수 없다.

```json
{
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresIn": 1800
}
```

## Logout

### `POST /api/auth/logout`

- 권한: `PUBLIC`
- Request Body: 없음
- Request Cookie: `refresh_token` (선택)
- `Authorization: Bearer` 헤더는 읽거나 검증하지 않는다. 만료된 Access JWT가 함께 전송되어도
  Refresh Cookie를 폐기한다.

Cookie가 있으면 대응하는 Redis Refresh Token을 삭제하고, 항상 만료된 `refresh_token` Cookie를 응답한다.
이미 만료·폐기된 Cookie 또는 Cookie가 없는 Logout도 성공으로 처리한다.

## Refresh Cookie

`refresh_token`은 `HttpOnly`, `Path=/api/auth`, host-only Domain, `SameSite=Lax`로 발급한다. 운영 HTTPS
환경에서는 `Secure=true`다. Refresh·Logout은 `Origin` 헤더가 `cking.cors.allowed-origins`의 허용 origin과
일치할 때만 수행한다.

## 오류 계약

구현 시 오류 응답은 공통 `ApiResponse`와 `ErrorCode` 규칙을 따른다. `VALIDATION_FAILED`,
`UNAUTHORIZED`, `FORBIDDEN`, `SYSTEM_ERROR`의 정본은 `CommonErrorCode`다. Auth 전용
`AuthErrorCode`에는 `INVALID_LOGIN_CODE`, `INVALID_REFRESH_TOKEN`을 둔다.

| 코드 | HTTP | 상황 | 클라이언트 처리 |
| --- | --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Login Code 요청 형식이 올바르지 않음 | 요청을 수정해 다시 시도 |
| `UNAUTHORIZED` | 401 | Access Token 없음·만료·변조·형식 오류 | OAuth 로그인을 다시 시작 |
| `INVALID_LOGIN_CODE` | 401 | Login Code 만료·소비·잘못된 값 | OAuth 로그인을 처음부터 다시 시작 |
| `INVALID_REFRESH_TOKEN` | 401 | Refresh Token 만료·폐기·재사용·잘못된 값 | OAuth 로그인을 처음부터 다시 시작 |
| `FORBIDDEN` | 403 | 인증되었지만 endpoint 권한이 부족함 | 재인증하지 않고 권한 없음으로 처리 |
| `SYSTEM_ERROR` | 500 | 예상하지 못한 인증 서버 오류 | 재시도 안내 또는 로그인 화면으로 이동 |

`POST /api/auth/token`의 Login Code 소비는 동시 요청에서도 한 번만 성공하도록 원자적으로 처리한다.
