# Auth API

이 문서는 Auth 외부 API의 **Sprint 2 목표 계약**이다. 현재 저장소에는 이 endpoint와 OAuth2 로그인·JWT
인증 구현이 없으며, 실제 인증 전환 전의 기존 API `userId` 계약은 [공통 API 규약](../../common/api.md)을
그대로 따른다. Spring Security의 기반 설정은 준비되어 있으나 `/api/**`는 현재 모두 허용된다.

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

Redirect URL에는 성공 시 `code` 또는 실패 시 `error` 중 하나만 넣는다. Access JWT, Refresh Token,
Provider 원문 오류와 내부 예외 상세는 redirect URL에 포함하지 않는다.

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
Set-Cookie: refresh_token=<opaque-token>; Path=/api/auth; Max-Age=1209600; HttpOnly;
            [운영 환경: Secure]; SameSite=<Lax | None>
```

- Cookie 이름은 `refresh_token`이며, `Domain` 속성을 설정하지 않는 host-only Cookie다.
- `Path=/api/auth`로 한정해 일반 Cking API 요청에는 Refresh Cookie를 보내지 않는다.
- 운영 환경에서는 `Secure=true`로 HTTPS에서만 전달한다.
- same-site 배포는 `SameSite=Lax`를 사용한다. Frontend와 API의 cross-site Cookie 전달이 필요하면
  `SameSite=None`과 `Secure=true`를 함께 사용한다.
- Cookie 기반 Refresh·Logout 요청은 Frontend가 credential을 포함해 전송한다. cross-site 배포에서는
  CORS가 허용 origin을 명시하고 credential을 허용해야 한다.
- `POST /api/auth/refresh`와 `POST /api/auth/logout`은 설정된 허용 origin과 일치하는 `Origin`만
  허용해 Cookie 기반 요청의 CSRF를 방어한다.

## Token 갱신

### `POST /api/auth/refresh`

- 권한: `PUBLIC`
- HttpOnly Cookie의 Refresh Token을 사용한다. Request Body는 없다.
- Redis에서 기존 Refresh Token을 원자적으로 한 번 소비한 뒤, 새 Refresh Token과 Access Token을
  발급한다. 동시에 도착한 동일 Cookie 요청은 하나만 성공한다.
- 응답 `data`는 [Token 교환](#token-교환)과 같고, 새 Refresh Cookie는
  [Refresh Cookie 계약](#refresh-cookie-계약)을 따른다.

## Logout

### `POST /api/auth/logout`

- 권한: `PUBLIC`
- HttpOnly Cookie의 Refresh Token이 있으면 `SHA-256(token)` 기반 Redis key를 삭제하고 Refresh Cookie를 만료한다.
- 이미 만료·폐기되었거나 Cookie가 없는 경우에도 클라이언트 Cookie 만료 처리를 수행한다.
- Cookie 만료 응답도 `refresh_token`, `Path=/api/auth`, host-only, `HttpOnly`, 운영 환경의 `Secure`,
  배포 구조에 맞는 `SameSite`를 동일하게 적용하고 `Max-Age=0`으로 설정한다.
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

구현 시 오류 응답은 공통 `ApiResponse`와 `ErrorCode` 규칙을 따른다. `VALIDATION_FAILED`,
`UNAUTHORIZED`, `FORBIDDEN`, `SYSTEM_ERROR`의 정본은 `CommonErrorCode`다. Auth 전용
`AuthErrorCode`에는 `INVALID_LOGIN_CODE`, `INVALID_REFRESH_TOKEN`만 둔다.

| 코드 | HTTP | 상황 | 클라이언트 처리 |
| --- | --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Login Code 요청 형식이 올바르지 않음 | 요청을 수정해 다시 시도 |
| `UNAUTHORIZED` | 401 | Access Token 없음·만료·변조·형식 오류 | Refresh Cookie가 있으면 Refresh를 시도하고, 없거나 실패하면 로그인 |
| `INVALID_LOGIN_CODE` | 401 | Login Code 만료·소비·잘못된 값 | OAuth 로그인을 처음부터 다시 시작 |
| `INVALID_REFRESH_TOKEN` | 401 | Refresh Token 만료·폐기·재사용·잘못된 값 | 현재 인증 상태를 폐기하고 로그인 |
| `FORBIDDEN` | 403 | 인증되었지만 endpoint 권한이 부족함 | Refresh하지 않고 권한 없음으로 처리 |
| `SYSTEM_ERROR` | 500 | 예상하지 못한 인증 서버 오류 | 재시도 안내 또는 로그인 화면으로 이동 |

`POST /api/auth/token`의 Login Code 소비와 `POST /api/auth/refresh`의 Refresh Token rotation은
동시 요청에서도 각각 한 번만 성공하도록 원자적으로 처리한다.
