# Auth API

이 문서는 Auth 외부 API의 **Sprint 2 목표 계약**이다. 현재 저장소에는 이 endpoint와
인증 구현이 없으며, 실제 인증 전환 전의 기존 API `userId` 계약은
[공통 API 규약](../../common/api.md)을 그대로 따른다.

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

Access JWT와 Refresh Token을 redirect URL에 포함하지 않는다.

## Token 교환

### `POST /api/auth/token`

- 권한: `PUBLIC`
- Request Body의 `code`는 Login Code 원문이다.

```json
{ "code": "..." }
```

Login Code를 검증하고 원자적으로 한 번 소비한 뒤 `memberId`로 Access Token과 Refresh Token을
발급한다. 응답은 Access Token 정보만 제공하며 Refresh Token은 [Refresh Cookie 계약](#refresh-cookie-계약)으로 전달한다.

```json
{
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresIn": 1800
}
```

### Refresh Cookie 계약

`POST /api/auth/token`과 `POST /api/auth/refresh`는 다음 속성으로 Refresh Token을 설정한다.

```text
Set-Cookie: refresh_token=<opaque-token>; Path=/api/auth; Max-Age=1209600;
            HttpOnly; Secure; SameSite=None
```

- Cookie 이름은 `refresh_token`이며, `Domain` 속성을 설정하지 않는 host-only Cookie다.
- `Path=/api/auth`로 한정해 일반 Cking API 요청에는 Refresh Cookie를 보내지 않는다.
- `Secure`이므로 HTTPS에서만 전달한다. `SameSite=None`은 Frontend와 API가 cross-site일 수 있는
  배포 구조에서도 credential Cookie 전달을 허용하기 위한 정책이다.
- Cookie 기반 Refresh·Logout 요청은 Frontend가 credential을 포함해 전송한다. 인증 구현 시 CORS는
  허용 origin을 명시하고 credential을 허용하며 `Authorization` header를 허용해야 한다.
- `POST /api/auth/refresh`와 `POST /api/auth/logout`은 설정된 허용 origin과 일치하는 `Origin`만
  허용해 Cookie 기반 요청의 CSRF를 방어한다.

## Token 갱신

### `POST /api/auth/refresh`

- 권한: `PUBLIC`
- HttpOnly Cookie의 Refresh Token을 사용한다. Request Body는 없다.
- Redis 상태를 확인한 뒤 기존 Refresh Token을 폐기하고 새 Refresh Token과 Access Token을 발급한다.
- 응답 `data`는 [Token 교환](#token-교환)과 같고, 새 Refresh Cookie는
  [Refresh Cookie 계약](#refresh-cookie-계약)을 따른다.

## Logout

### `POST /api/auth/logout`

- 권한: `PUBLIC`
- HttpOnly Cookie의 Refresh Token이 있으면 Redis에서 제거하고 Refresh Cookie를 만료한다.
- 이미 만료·폐기되었거나 Cookie가 없는 경우에도 클라이언트 Cookie 만료 처리를 수행한다.
- Cookie 만료 응답도 `refresh_token`, `Path=/api/auth`, host-only, `HttpOnly`, `Secure`,
  `SameSite=None`을 동일하게 적용하고 `Max-Age=0`으로 설정한다.
- 성공 시 `200 OK`와 공통 성공 응답을 반환한다.

## 현재 사용자 조회

### `GET /api/me`

- 권한: `USER`, `ADMIN`
- Access JWT를 검증해 얻은 현재 `memberId`만 조회한다. 호출자 `userId` query parameter나 body는 받지 않는다.

```json
{
  "memberId": 1,
  "name": "홍길동",
  "email": "user@example.com",
  "role": "USER",
  "creator": false
}
```

`creator`는 JWT claim이 아니라 `creator.member_id` 존재 여부를 반영한다.

## 오류 계약

구현 시 오류 응답은 공통 `ApiResponse`와 `ErrorCode` 규칙을 따른다. 아래 Auth 전용 코드의
정본은 향후 `AuthErrorCode` enum이며, `VALIDATION_FAILED`와 `FORBIDDEN`은 기존 공통 코드를 사용한다.

| 코드 | HTTP | 상황 | 클라이언트 처리 |
| --- | --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Login Code 요청 형식이 올바르지 않음 | 요청을 수정해 다시 시도 |
| `AUTHENTICATION_REQUIRED` | 401 | 보호 API에 Access Token이 없음 | Refresh Cookie가 있으면 Refresh를 시도하고, 없거나 실패하면 로그인 |
| `INVALID_ACCESS_TOKEN` | 401 | Access JWT의 서명·형식·claim이 유효하지 않음 | Refresh하지 않고 현재 인증 상태를 폐기한 뒤 로그인 |
| `EXPIRED_ACCESS_TOKEN` | 401 | Access JWT가 만료됨 | Refresh를 한 번 시도하고, 실패하면 로그인 |
| `INVALID_LOGIN_CODE` | 401 | Login Code가 유효하지 않음 | OAuth 로그인을 처음부터 다시 시작 |
| `EXPIRED_LOGIN_CODE` | 401 | Login Code의 60초 TTL이 지남 | OAuth 로그인을 처음부터 다시 시작 |
| `CONSUMED_LOGIN_CODE` | 401 | Login Code가 이미 교환에 성공해 소비됨 | OAuth 로그인을 처음부터 다시 시작 |
| `INVALID_REFRESH_TOKEN` | 401 | Refresh Token의 형식·Hash 또는 활성 Redis 기록이 유효하지 않음 | 현재 인증 상태를 폐기하고 로그인 |
| `EXPIRED_REFRESH_TOKEN` | 401 | Refresh Token의 14일 TTL이 지남 | 현재 인증 상태를 폐기하고 로그인 |
| `REVOKED_REFRESH_TOKEN` | 401 | Logout 또는 rotation으로 Refresh Token이 폐기됨 | 현재 인증 상태를 폐기하고 로그인 |
| `FORBIDDEN` | 403 | 인증되었지만 endpoint 권한이 부족함 | Refresh하지 않고 권한 없음으로 처리 |

Login Code와 Refresh Token은 소비·폐기 뒤에도 남은 원래 TTL 동안 상태를 식별할 수 있어야 한다.
그래야 `INVALID`, `EXPIRED`, `CONSUMED`/`REVOKED` 오류 코드를 계약대로 구분할 수 있다.

`POST /api/auth/token`의 Login Code 소비와 `POST /api/auth/refresh`의 Refresh Token rotation은
동시 요청에서도 각각 한 번만 성공하도록 원자적으로 처리한다.
